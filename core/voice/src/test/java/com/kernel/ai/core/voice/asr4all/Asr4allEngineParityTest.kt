package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem

/**
 * Row-by-row parity with FUTO's pinned Python driver (`onnx/asr4all_onnx.py` at
 * futo-org/asr4all-s@87a6b5d093723fe37e9f69ad26307a3b95a31af0).
 *
 * Expectations, model-file SHA-256 values, and partial outputs live in
 * `src/test/resources/asr4all/parity-expected.tsv` (text only). Audio and model files are never
 * committed. Inputs:
 *   ASR4ALL_MODEL_DIR     exact pinned model dir (S: full S bundle, M: full M bundle)
 *   ASR4ALL_PARITY_MODEL  S or M; selects the expected rows for that model dir
 *   ASR4ALL_FIXTURE_WAV   16 kHz mono s16 wav; its SHA-256 must equal the one in the expectation file
 *
 * Two modes:
 *   ordinary (default): rows skip when inputs are absent. Skips are visible in the XML.
 *   ASR4ALL_PARITY=required: missing or mismatched inputs FAIL, so the run cannot pass by skipping.
 * Fixture and full selected-directory model identities are checked in both modes.
 */
class Asr4allEngineParityTest {

    private val required = System.getenv("ASR4ALL_PARITY") == "required"
    private val modelDir: File? = System.getenv("ASR4ALL_MODEL_DIR")?.let(::File)
    private val modelName: String? = System.getenv("ASR4ALL_PARITY_MODEL")
    private val fixture: File? = System.getenv("ASR4ALL_FIXTURE_WAV")?.let(::File)

    private val expectation: ParityFile by lazy { ParityFile.load() }

    /** Enforces the input contract. Ordinary mode skips; required mode fails. */
    private fun requireInputs(): Pair<File, File> {
        val missing = buildList {
            if (modelDir == null || !modelDir.isDirectory) add("ASR4ALL_MODEL_DIR (directory)")
            if (modelName != "S" && modelName != "M") add("ASR4ALL_PARITY_MODEL (S or M)")
            if (fixture == null || !fixture.isFile) add("ASR4ALL_FIXTURE_WAV (file)")
        }
        if (missing.isNotEmpty()) {
            val msg = "asr4all parity inputs missing: ${missing.joinToString()}"
            if (required) throw AssertionError(msg)
            assumeTrue(false, msg)
        }
        return modelDir!! to fixture!!
    }
    /** Loads the fixture only after enforcing its complete model and audio identity. */
    private fun checkedInputs(): Pair<File, FloatArray> {
        val (dir, wav) = requireInputs()
        checkFixtureSha(wav)
        checkModelIdentity(dir)
        return dir to clip(wav)
    }


    private fun checkFixtureSha(file: File) {
        assertEquals(expectation.fixtureSha256, sha256(file), "fixture SHA-256 does not match")
    }

    private fun checkModelIdentity(dir: File) {
        val expected = expectation.modelHashesFor(modelName!!)
        val files = dir.listFiles()?.filter { it.isFile } ?: error("cannot list model directory $dir")
        val modelFiles = files.filterNot { it.name == PINNED_DRIVER }
        assertEquals(expected.keys, modelFiles.map { it.name }.toSet(), "model file set differs")
        for (file in modelFiles) {
            assertEquals(expected.getValue(file.name), sha256(file), "model SHA-256 differs: ${file.name}")
        }
        files.singleOrNull { it.name == PINNED_DRIVER }?.let { driver ->
            assertEquals(PINNED_DRIVER_SHA256, sha256(driver), "Python reference driver SHA-256 differs")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun clip(file: File): FloatArray {
        val stream = AudioSystem.getAudioInputStream(file)
        val format = stream.format
        assertEquals(16_000f, format.sampleRate, 0.0f)
        assertEquals(1, format.channels)
        val bytes = stream.readBytes()
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return samples
    }

    /** One DynamicTest per expected row for the selected model, so each row reports pass or fail. */
    @TestFactory
    fun perRowParity(): List<DynamicTest> {
        val (dir, wav) = requireInputs()
        checkFixtureSha(wav)
        checkModelIdentity(dir)
        val samples = clip(wav)
        return expectation.rowsFor(modelName!!).map { row ->
            DynamicTest.dynamicTest("${row.model} ${row.tier} ${row.mode}") {
                Asr4allEngine.open(dir, row.tier).use { engine ->
                    val actual = when (row.mode) {
                        "offline_pcec" -> engine.decodeOffline(samples, applyPcec = true)
                        "offline_plain" -> engine.decodeOffline(samples, applyPcec = false)
                        "stream_pcec" -> {
                            streamAll(engine, samples)
                            engine.finish()
                            engine.decodePcec(decodeTail = true)
                        }
                        "partial_pcec" -> {
                            for (chunk in 0..PARTIAL_CHUNK_INDEX) {
                                val start = chunk * STREAM_CHUNK
                                engine.decodeStream(samples.copyOfRange(start, minOf(start + STREAM_CHUNK, samples.size)))
                            }
                            engine.decodePcec(decodeTail = true)
                        }
                        else -> error("unknown mode ${row.mode}")
                    }
                    assertEquals(row.text, actual, "row ${row.model} ${row.tier} ${row.mode}")
                }
            }
        }
    }

    private fun streamAll(engine: Asr4allEngine, samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            engine.decodeStream(samples.copyOfRange(i, minOf(i + STREAM_CHUNK, samples.size)))
            i += STREAM_CHUNK
        }
    }

    @TestFactory
    fun resetAfterAcousticAndPcecCarryMatchesFreshStreaming(): List<DynamicTest> {
        val (dir, samples) = checkedInputs()
        return expectation.rowsFor(modelName!!).map { it.tier }.distinct().map { tier ->
            DynamicTest.dynamicTest("$modelName $tier reset after acoustic and PCEC carry") {
                Asr4allEngine.open(dir, tier).use { reused ->
                    var seededPcecCarry = false
                    for (iteration in 0 until MAX_SEED_CLIPS) {
                        streamAll(reused, samples)
                        assertTrue(reused.decodedFrameCount > 0, "seed fixture must decode acoustic frames")
                        if (reused.decodePcec().isNotBlank()) {
                            seededPcecCarry = true
                            break
                        }
                    }
                    assertTrue(seededPcecCarry, "seed fixture must produce committed PCEC output before reset")

                    reused.reset()
                    streamAll(reused, samples)
                    val resetFrames = reused.decodedFrameCount
                    val resetPartial = reused.decodePcec()
                    reused.finish()
                    val resetFinal = reused.decodePcec(decodeTail = true)

                    Asr4allEngine.open(dir, tier).use { fresh ->
                        streamAll(fresh, samples)
                        assertEquals(resetFrames, fresh.decodedFrameCount, "reset and fresh streams must decode equal frame counts")
                        assertEquals(
                            fresh.decodePcec(),
                            resetPartial,
                            "partial output after reset must match a fresh stream",
                        )
                        fresh.finish()
                        val freshFinal = fresh.decodePcec(decodeTail = true)
                        assertEquals(freshFinal, resetFinal, "final output after reset must match a fresh stream")
                        assertEquals(
                            expectation.textFor(modelName!!, tier, "stream_pcec"),
                            resetFinal,
                            "reset stream must retain pinned-driver parity",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun closedEngineRejectsUseAndReopenWorks() {
        val (dir, samples) = checkedInputs()
        val tier = expectation.rowsFor(modelName!!).first().tier
        val engine = Asr4allEngine.open(dir, tier)
        engine.close()
        assertTrue(engine.isClosed)
        assertThrows(IllegalStateException::class.java) { engine.decodeStream(FloatArray(16)) }
        Asr4allEngine.open(dir, tier).use { reopened ->
            assertEquals(expectation.textFor(modelName!!, tier, "offline_pcec"), reopened.decodeOffline(samples, applyPcec = true))
        }
    }

    private companion object {
        const val STREAM_CHUNK = 8000
        const val PARTIAL_CHUNK_INDEX = 6
        const val PINNED_DRIVER = "asr4all_onnx.py"
        const val PINNED_DRIVER_SHA256 = "f173c6d61a004d8596d6f9b3623716167176639440920a92917f902d5566bba4"
        const val MAX_SEED_CLIPS = 3
    }
}

private data class ParityRow(val model: String, val tier: String, val mode: String, val text: String)

private class ParityFile(
    val fixtureSha256: String,
    private val rows: List<ParityRow>,
    private val modelHashes: Map<String, Map<String, String>>,
) {
    fun rowsFor(model: String): List<ParityRow> = rows.filter { it.model == model }
    fun textFor(model: String, tier: String, mode: String): String =
        rows.single { it.model == model && it.tier == tier && it.mode == mode }.text
    fun modelHashesFor(model: String): Map<String, String> = modelHashes.getValue(model)

    companion object {
        fun load(): ParityFile {
            val lines = Asr4allEngineParityTest::class.java.classLoader
                .getResourceAsStream("asr4all/parity-expected.tsv")!!.bufferedReader().readLines()
            val sha = lines.single { it.startsWith("# fixture sha256 ") }
                .removePrefix("# fixture sha256 ").substringBefore(" ")
            val hashRows = lines.filter {
                it.startsWith("# model-sha256\t") && !it.startsWith("# model-sha256\tmodel\t")
            }.map { line ->
                val (_, model, file, digest) = line.removePrefix("# ").split('\t')
                Triple(model, file, digest)
            }
            assertEquals(hashRows.size, hashRows.map { it.first to it.second }.toSet().size, "duplicate model hash")
            val hashes = hashRows.groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, entries) -> entries.toMap() }
            assertEquals(setOf("S", "M"), hashes.keys, "model hash manifest must cover exactly S and M")
            assertEquals(
                setOf(
                    "metadata.json", "stream_c16r4.onnx", "stream_c32r8.onnx", "stream_c64r16.onnx",
                    "stream_c128r24.onnx", "stream_c256r32.onnx", "stream_c512r8.onnx",
                    "pcec_c16.onnx", "pcec_c16_flush.onnx", "pcec_c64.onnx", "pcec_c64_flush.onnx",
                    "vad.onnx", "asr4all.onnx.data",
                ),
                hashes.getValue("S").keys,
                "S model hash manifest file set differs",
            )
            assertEquals(
                setOf(
                    "metadata.json", "stream_c16r4.onnx", "stream_c32r8.onnx", "stream_c64r16.onnx",
                    "stream_c128r24.onnx", "stream_c256r32.onnx", "stream_c512r8.onnx",
                    "pcec_c16.onnx", "pcec_c16_flush.onnx", "pcec_c64.onnx", "pcec_c64_flush.onnx",
                    "vad.onnx", "asr4all.onnx.data",
                ),
                hashes.getValue("M").keys,
                "M model hash manifest file set differs",
            )
            val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map {
                val (m, t, mode, text) = it.split("\t", limit = 4)
                ParityRow(m, t, mode, text)
            }
            val keys = rows.map { Triple(it.model, it.tier, it.mode) }
            assertEquals(keys.size, keys.toSet().size, "duplicate parity row")
            val expected = buildSet {
                for (model in listOf("S", "M"))
                    for (tier in listOf("stream_c16r4", "stream_c32r8"))
                        for (mode in listOf("offline_pcec", "offline_plain", "stream_pcec", "partial_pcec"))
                            add(Triple(model, tier, mode))
            }
            assertEquals(expected, keys.toSet(), "parity row coverage differs")
            return ParityFile(sha, rows, hashes)
        }
    }
}
