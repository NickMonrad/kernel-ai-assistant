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
 * Expectations live in `src/test/resources/asr4all/parity-expected.tsv` (text only). Audio and model
 * files are never committed. Inputs:
 *   ASR4ALL_MODEL_DIR     model dir (S: trimmed onnx-min, M: m bundle)
 *   ASR4ALL_PARITY_MODEL  S or M; selects the expected rows for that model dir
 *   ASR4ALL_FIXTURE_WAV   16 kHz mono s16 wav; its SHA-256 must equal the one in the expectation file
 *
 * Two modes:
 *   ordinary (default): rows skip when inputs are absent. Skips are visible in the XML.
 *   ASR4ALL_PARITY=required: missing or mismatched inputs FAIL, so the run cannot pass by skipping.
 * A fixture whose SHA differs from the expectation file fails in both modes.
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

    private fun checkFixtureSha(file: File) {
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectation.fixtureSha256, sha, "fixture SHA-256 does not match the expectation file")
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
        val samples = clip(wav)
        return expectation.rowsFor(modelName!!).map { row ->
            DynamicTest.dynamicTest("${row.model} ${row.tier} ${row.mode}") {
                Asr4allEngine.open(dir, row.tier).use { engine ->
                    val actual = when (row.mode) {
                        "offline_pcec" -> engine.decodeOffline(samples, applyPcec = true)
                        "offline_plain" -> engine.decodeOffline(samples, applyPcec = false)
                        "stream_pcec" -> {
                            var i = 0
                            while (i < samples.size) {
                                engine.decodeStream(samples.copyOfRange(i, minOf(i + STREAM_CHUNK, samples.size)))
                                i += STREAM_CHUNK
                            }
                            engine.finish()
                            engine.decodePcec(decodeTail = true)
                        }
                        else -> error("unknown mode ${row.mode}")
                    }
                    assertEquals(row.text, actual, "row ${row.model} ${row.tier} ${row.mode}")
                }
            }
        }
    }

    @Test
    fun cancelMidUtteranceThenRestartMatchesFreshDecode() {
        val (dir, wav) = requireInputs()
        checkFixtureSha(wav)
        val samples = clip(wav)
        val tier = expectation.rowsFor(modelName!!).first().tier
        Asr4allEngine.open(dir, tier).use { engine ->
            engine.decodeStream(samples.copyOfRange(0, 8000))
            engine.reset()
            assertEquals(expectation.textFor(modelName, tier, "offline_pcec"), engine.decodeOffline(samples, applyPcec = true))
        }
    }

    @Test
    fun closedEngineRejectsUseAndReopenWorks() {
        val (dir, wav) = requireInputs()
        val tier = expectation.rowsFor(modelName!!).first().tier
        val engine = Asr4allEngine.open(dir, tier)
        engine.close()
        assertTrue(engine.isClosed)
        assertThrows(IllegalStateException::class.java) { engine.decodeStream(FloatArray(16)) }
        Asr4allEngine.open(dir, tier).use { reopened ->
            assertEquals(expectation.textFor(modelName!!, tier, "offline_pcec"), reopened.decodeOffline(clip(wav), applyPcec = true))
        }
    }

    private companion object {
        const val STREAM_CHUNK = 8000
    }
}

private data class ParityRow(val model: String, val tier: String, val mode: String, val text: String)

private class ParityFile(val fixtureSha256: String, private val rows: List<ParityRow>) {
    fun rowsFor(model: String): List<ParityRow> = rows.filter { it.model == model }
    fun textFor(model: String, tier: String, mode: String): String =
        rows.single { it.model == model && it.tier == tier && it.mode == mode }.text

    companion object {
        fun load(): ParityFile {
            val lines = Asr4allEngineParityTest::class.java.classLoader
                .getResourceAsStream("asr4all/parity-expected.tsv")!!.bufferedReader().readLines()
            val sha = lines.single { it.startsWith("# fixture sha256 ") }.removePrefix("# fixture sha256 ").substringBefore(" ")
            val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map {
                val (m, t, mode, text) = it.split("\t", limit = 4)
                ParityRow(m, t, mode, text)
            }
            return ParityFile(sha, rows)
        }
    }
}
