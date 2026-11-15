package com.kernel.ai.core.skills

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Versioned, hash-bound storage for the generated MiniLM phrase vectors. */
object MiniLMIntentPhraseVectorAsset {
    const val FILE_NAME = "intent_phrase_vectors.bin"
    const val EMBEDDING_DIMENSION = 384
    const val FORMAT_VERSION = 1

    private val MAGIC = "JNDLMVEC".toByteArray(StandardCharsets.US_ASCII)
    private const val DIGEST_BYTES = 32
    private const val HEADER_SIZE = 8 + 4 * 4 + DIGEST_BYTES * 4
    private const val MAX_INTENTS = 1_000
    private const val MAX_PHRASES = 10_000
    private const val MAX_INTENT_NAME_BYTES = 256

    data class SourceHashes(
        val model: String,
        val vocab: String,
        val phrases: String,
    )

    data class IntentPhraseCount(val intent: String, val phraseCount: Int)

    sealed interface LoadResult {
        data class Loaded(val vectors: Map<String, List<FloatArray>>) : LoadResult
        data class Rejected(val reason: String) : LoadResult
    }

    /** Uses the same Android org.json parser/order as the runtime phrase builder. */
    fun phraseCounts(phrasesJson: String): List<IntentPhraseCount> {
        val intents = JSONObject(phrasesJson).getJSONObject("intents")
        return intents.keys().asSequence().map { name ->
            IntentPhraseCount(name, intents.getJSONObject(name).getJSONArray("phrases").length())
        }.toList()
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(bytes: ByteArray, offset: Int, length: Int): String =
        MessageDigest.getInstance("SHA-256").apply { update(bytes, offset, length) }.digest().toHex()

    fun encode(
        vectors: Map<String, List<FloatArray>>,
        phraseCounts: List<IntentPhraseCount>,
        sourceHashes: SourceHashes,
    ): ByteArray {
        requireValidHashes(sourceHashes)
        require(phraseCounts.isNotEmpty() && phraseCounts.size <= MAX_INTENTS)
        require(phraseCounts.map { it.intent }.toSet().size == phraseCounts.size)
        require(phraseCounts.all { it.phraseCount > 0 })
        require(vectors.keys == phraseCounts.map { it.intent }.toSet())
        require(phraseCounts.all { vectors.getValue(it.intent).size == it.phraseCount })
        val phraseCount = phraseCounts.sumOf { it.phraseCount }
        require(phraseCount <= MAX_PHRASES)

        val payloadBuffer = ByteArrayOutputStream()
        DataOutputStream(payloadBuffer).use { payload ->
            for (group in phraseCounts) {
                val name = group.intent.toByteArray(StandardCharsets.UTF_8)
                require(name.isNotEmpty() && name.size <= MAX_INTENT_NAME_BYTES)
                payload.writeInt(name.size)
                payload.write(name)
                payload.writeInt(group.phraseCount)
                for (vector in vectors.getValue(group.intent)) {
                    require(vector.size == EMBEDDING_DIMENSION)
                    vector.forEach { value ->
                        require(value.isFinite())
                        payload.writeFloat(value)
                    }
                }
            }
        }
        val payload = payloadBuffer.toByteArray()
        val result = ByteArrayOutputStream(HEADER_SIZE + payload.size)
        DataOutputStream(result).use { output ->
            output.write(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(EMBEDDING_DIMENSION)
            output.writeInt(phraseCounts.size)
            output.writeInt(phraseCount)
            output.write(sourceHashes.model.fromHex())
            output.write(sourceHashes.vocab.fromHex())
            output.write(sourceHashes.phrases.fromHex())
            output.write(sha256(payload).fromHex())
            output.write(payload)
        }
        return result.toByteArray()
    }

    fun load(
        bytes: ByteArray,
        expectedHashes: SourceHashes,
        expectedPhraseCounts: List<IntentPhraseCount>,
    ): LoadResult {
        return try {
            requireValidHashes(expectedHashes)
            if (bytes.size < HEADER_SIZE) return LoadResult.Rejected("truncated header")
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) return LoadResult.Rejected("bad magic")
            val version = input.readInt()
            if (version != FORMAT_VERSION) return LoadResult.Rejected("unsupported version $version")
            val dimension = input.readInt()
            if (dimension != EMBEDDING_DIMENSION) return LoadResult.Rejected("embedding dimension mismatch")
            val intentCount = input.readInt()
            val phraseCount = input.readInt()
            if (intentCount !in 1..MAX_INTENTS || phraseCount !in 1..MAX_PHRASES) {
                return LoadResult.Rejected("invalid vector counts")
            }

            val actualHashes = SourceHashes(
                model = ByteArray(DIGEST_BYTES).also(input::readFully).toHex(),
                vocab = ByteArray(DIGEST_BYTES).also(input::readFully).toHex(),
                phrases = ByteArray(DIGEST_BYTES).also(input::readFully).toHex(),
            )
            if (actualHashes != expectedHashes) return LoadResult.Rejected("source hash mismatch")
            val payloadHash = ByteArray(DIGEST_BYTES).also(input::readFully).toHex()
            if (input.available() == 0) return LoadResult.Rejected("empty vector payload")
            if (sha256(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE) != payloadHash) {
                return LoadResult.Rejected("payload checksum mismatch")
            }

            val expectedCounts = expectedPhraseCounts.associate { it.intent to it.phraseCount }
            if (expectedCounts.size != expectedPhraseCounts.size || intentCount != expectedCounts.size ||
                phraseCount != expectedPhraseCounts.sumOf { it.phraseCount }
            ) {
                return LoadResult.Rejected("phrase shape mismatch")
            }
            val payloadInput = DataInputStream(ByteArrayInputStream(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE))
            val vectors = HashMap<String, List<FloatArray>>()
            var decodedPhraseCount = 0
            repeat(intentCount) {
                val nameLength = payloadInput.readInt()
                if (nameLength !in 1..MAX_INTENT_NAME_BYTES || nameLength > payloadInput.available()) {
                    return LoadResult.Rejected("invalid intent name length")
                }
                val name = ByteArray(nameLength).also(payloadInput::readFully)
                    .toString(StandardCharsets.UTF_8)
                val count = payloadInput.readInt()
                if (expectedCounts[name] != count || vectors.containsKey(name) ||
                    count !in 1..MAX_PHRASES || count.toLong() * EMBEDDING_DIMENSION * 4 > payloadInput.available().toLong()
                ) {
                    return LoadResult.Rejected("phrase shape mismatch for $name")
                }
                val group = ArrayList<FloatArray>(count)
                repeat(count) {
                    val vector = FloatArray(EMBEDDING_DIMENSION)
                    for (index in vector.indices) {
                        val value = payloadInput.readFloat()
                        if (!value.isFinite()) return LoadResult.Rejected("non-finite vector value")
                        vector[index] = value
                    }
                    group += vector
                }
                decodedPhraseCount += count
                vectors[name] = group
            }
            if (payloadInput.available() != 0 || decodedPhraseCount != phraseCount || vectors.size != expectedCounts.size) {
                return LoadResult.Rejected("trailing or incomplete vector payload")
            }
            LoadResult.Loaded(vectors)
        } catch (exception: Exception) {
            LoadResult.Rejected("malformed asset (${exception.javaClass.simpleName})")
        }
    }

    private fun requireValidHashes(hashes: SourceHashes) {
        require(listOf(hashes.model, hashes.vocab, hashes.phrases).all { HASH_PATTERN.matches(it) }) {
            "Source hashes must be lowercase SHA-256 hex"
        }
    }

    private fun String.fromHex(): ByteArray = ByteArray(DIGEST_BYTES) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String {
        val characters = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xff
            characters[index * 2] = HEX_DIGITS[value ushr 4]
            characters[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(characters)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private val HASH_PATTERN = Regex("[0-9a-f]{64}")
}
