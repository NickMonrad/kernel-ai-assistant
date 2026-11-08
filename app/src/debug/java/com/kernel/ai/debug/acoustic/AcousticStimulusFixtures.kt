package com.kernel.ai.debug.acoustic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal const val MAX_FIXTURE_DURATION_MS = 5_000L
private const val SAMPLE_RATE_HZ = 48_000
private const val CHANNEL_COUNT = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
private const val MAX_DATA_BYTES = BYTES_PER_SECOND * (MAX_FIXTURE_DURATION_MS / 1_000)
private const val MAX_FILE_BYTES = MAX_DATA_BYTES + 4_096L

internal object AcousticFixtureStorage {
    const val DIRECTORY_NAME = "acoustic-fixtures"
    const val MANIFEST_FILE_NAME = "manifest.json"
    const val RESULTS_DIRECTORY_NAME = "acoustic-stimulus-results"

    fun fixtureDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)
    fun manifestFile(context: Context): File = File(fixtureDirectory(context), MANIFEST_FILE_NAME)
    fun resultDirectory(context: Context): File = File(context.filesDir, RESULTS_DIRECTORY_NAME)
}

data class FixtureManifest(
    val schemaVersion: Int,
    val fixtures: List<FixtureEntry>,
)

data class FixtureEntry(
    val fixtureId: String,
    val fileName: String,
    val sha256: String,
    val durationMs: Long,
)

data class ResolvedFixture(
    val entry: FixtureEntry,
    val file: File,
    val metadata: WavMetadata,
)

class FixtureValidationException(val category: String) : Exception(category)

fun interface FixtureManifestReader {
    fun read(file: File): FixtureManifest
}

internal class JsonFixtureManifestReader : FixtureManifestReader {
    override fun read(file: File): FixtureManifest {
        if (!file.isFile || file.length() == 0L) {
            throw FixtureValidationException("fixture_manifest_missing")
        }
        val root = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            throw FixtureValidationException("fixture_manifest_invalid")
        }
        val schemaVersion = root.optInt("schema_version", -1)
        val array = root.optJSONArray("fixtures")
        if (schemaVersion != 1 || array == null) {
            throw FixtureValidationException("fixture_manifest_invalid")
        }
        val fixtures = mutableListOf<FixtureEntry>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
                ?: throw FixtureValidationException("fixture_manifest_invalid")
            val fixtureId = entry.optString("fixture_id", "")
            val fileName = entry.optString("file_name", "")
            val sha256 = entry.optString("sha256", "").sha256Normalised()
            val durationMs = entry.optLong("duration_ms", -1L)
            if (!isManifestId(fixtureId) || fileName.isBlank() ||
                !isSha256(sha256) || durationMs <= 0L || durationMs > MAX_FIXTURE_DURATION_MS
            ) {
                throw FixtureValidationException("fixture_manifest_invalid")
            }
            if (fixtures.any { it.fixtureId == fixtureId }) {
                throw FixtureValidationException("fixture_manifest_invalid")
            }
            fixtures += FixtureEntry(fixtureId, fileName, sha256, durationMs)
        }
        return FixtureManifest(schemaVersion, fixtures)
    }
}

internal interface FixtureSource {
    fun resolveAndValidate(fixtureId: String): ResolvedFixture
    fun openFixture(fixture: ResolvedFixture): java.io.FileInputStream
}


internal class FileFixtureRepository(
    private val fixtureDirectory: File,
    private val manifestReader: FixtureManifestReader = JsonFixtureManifestReader(),
) : FixtureSource {
    override fun resolveAndValidate(fixtureId: String): ResolvedFixture {
        val manifest = try {
            manifestReader.read(File(fixtureDirectory, AcousticFixtureStorage.MANIFEST_FILE_NAME))
        } catch (error: FixtureValidationException) {
            throw error
        } catch (_: Exception) {
            throw FixtureValidationException("fixture_manifest_invalid")
        }
        val entry = manifest.fixtures.firstOrNull { it.fixtureId == fixtureId }
            ?: throw FixtureValidationException("unknown_fixture")
        if (!isSafeFileName(entry.fileName)) {
            throw FixtureValidationException("arbitrary_path_not_allowed")
        }
        val root = fixtureDirectory.canonicalFile
        val file = File(root, entry.fileName).canonicalFile
        if (file.parentFile != root) {
            throw FixtureValidationException("arbitrary_path_not_allowed")
        }
        if (!file.isFile) {
            throw FixtureValidationException("fixture_missing")
        }
        if (file.length() == 0L) {
            throw FixtureValidationException("fixture_empty")
        }
        if (file.length() > MAX_FILE_BYTES) {
            throw FixtureValidationException("fixture_duration_not_supported")
        }
        val metadata = try {
            WavValidator.validate(file)
        } catch (error: FixtureValidationException) {
            throw error
        } catch (_: Exception) {
            throw FixtureValidationException("malformed_wav")
        }
        if (metadata.durationMs > MAX_FIXTURE_DURATION_MS) {
            throw FixtureValidationException("fixture_duration_not_supported")
        }
        if (metadata.sha256 != entry.sha256 || metadata.durationMs != entry.durationMs) {
            throw FixtureValidationException("fixture_hash_or_metadata_mismatch")
        }
        return ResolvedFixture(entry, file, metadata)
    }

    override fun openFixture(fixture: ResolvedFixture): java.io.FileInputStream =
        java.io.FileInputStream(fixture.file)
}

data class WavMetadata(
    val sha256: String,
    val durationMs: Long,
    val dataBytes: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
)

internal object WavValidator {
    fun validate(file: File): WavMetadata {
        if (file.length() == 0L) throw FixtureValidationException("fixture_empty")
        val parsed = RandomAccessFile(file, "r").use { raf -> parse(raf) }
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return parsed.copy(sha256 = digest.digest().toHex())
    }

    private fun parse(raf: RandomAccessFile): WavMetadata {
        val length = raf.length()
        if (length < 12L || readAscii(raf, 4) != "RIFF") {
            throw FixtureValidationException("malformed_wav")
        }
        val riffSize = readUInt32(raf)
        if (riffSize < 4L || riffSize + 8L > length || readAscii(raf, 4) != "WAVE") {
            throw FixtureValidationException("malformed_wav")
        }
        var fmtSeen = false
        var dataBytes = -1L
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (raf.filePointer + 8L <= length) {
            val chunkId = readAscii(raf, 4)
            val chunkSize = readUInt32(raf)
            val chunkStart = raf.filePointer
            val chunkEnd = chunkStart + chunkSize
            if (chunkEnd < chunkStart || chunkEnd > length) {
                throw FixtureValidationException("malformed_wav")
            }
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L) throw FixtureValidationException("malformed_wav")
                    val format = readUInt16(raf)
                    channels = readUInt16(raf)
                    sampleRate = readUInt32(raf).toInt()
                    readUInt32(raf)
                    val blockAlign = readUInt16(raf)
                    bits = readUInt16(raf)
                    if (format != 1 || channels != CHANNEL_COUNT || sampleRate != SAMPLE_RATE_HZ ||
                        bits != BITS_PER_SAMPLE || blockAlign != 2
                    ) {
                        throw FixtureValidationException("unsupported_wav_format")
                    }
                    fmtSeen = true
                }
                "data" -> {
                    if (dataBytes >= 0L) throw FixtureValidationException("malformed_wav")
                    dataBytes = chunkSize
                }
            }
            raf.seek(chunkEnd + (chunkSize and 1L))
        }
        if (!fmtSeen) throw FixtureValidationException("malformed_wav")
        if (dataBytes <= 0L) throw FixtureValidationException("fixture_empty")
        if (dataBytes > MAX_DATA_BYTES) throw FixtureValidationException("fixture_duration_not_supported")
        if (dataBytes % 2L != 0L) throw FixtureValidationException("malformed_wav")
        val durationMs = dataBytes * 1_000L / BYTES_PER_SECOND
        if (durationMs <= 0L) throw FixtureValidationException("fixture_empty")
        if (durationMs > MAX_FIXTURE_DURATION_MS) throw FixtureValidationException("fixture_duration_not_supported")
        return WavMetadata(
            sha256 = "",
            durationMs = durationMs,
            dataBytes = dataBytes,
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bits,
        )
    }

    private fun readAscii(raf: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readUInt16(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readUInt32(raf: RandomAccessFile): Long =
        (raf.readUnsignedByte().toLong()) or
            (raf.readUnsignedByte().toLong() shl 8) or
            (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 24)
}

private fun isManifestId(value: String): Boolean =
    value.length in 1..AcousticStimulusContract.MAX_FIXTURE_ID_LENGTH &&
        Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(value)

private fun isSafeFileName(value: String): Boolean =
    value.isNotBlank() && value == File(value).name && value != "." && value != ".."

private fun isSha256(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
