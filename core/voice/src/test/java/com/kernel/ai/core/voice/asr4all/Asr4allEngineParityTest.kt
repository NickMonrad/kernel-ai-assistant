package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * Parity with FUTO's reference driver (`onnx/asr4all_onnx.py`) at revision
 * futo-org/asr4all-s@87a6b5d093723fe37e9f69ad26307a3b95a31af0.
 *
 * Audio and model files are NOT committed. Set:
 *   ASR4ALL_MODEL_DIR     release `onnx/` dir (or a trimmed copy with the tiers under test)
 *   ASR4ALL_FIXTURE_WAV   16 kHz mono 16-bit wav, the clip the expected strings were generated from
 * Without them the tests are skipped, so CI without the model reports skipped, not passed.
 *
 * Expected strings: generated with
 *   python asr4all_onnx.py <onnx-dir> <clip>.wav --tier 0            # c16r4, PCEC
 *   python asr4all_onnx.py <onnx-dir> <clip>.wav --tier 0 --no-pcec  # c16r4, plain
 *   python asr4all_onnx.py <onnx-dir> <clip>.wav --tier 1            # c32r8, PCEC
 *   python asr4all_onnx.py <onnx-dir> <clip>.wav --tier 0 --stream   # chunked, final `pcec:` line
 * with the clip made by `espeak-ng -v en-us -w clip.wav "<text>"` then
 * `ffmpeg -i clip.wav -ar 16000 -ac 1 -sample_fmt s16 clip16.wav`. The clip is synthetic speech,
 * so this is a parity check of the port, not an accuracy measurement.
 */
class Asr4allEngineParityTest {

    private val modelDir: File? = System.getenv("ASR4ALL_MODEL_DIR")?.let(::File)?.takeIf { it.isDirectory }
    private val fixture: File? = System.getenv("ASR4ALL_FIXTURE_WAV")?.let(::File)?.takeIf { it.isFile }

    private fun clip(): FloatArray {
        val stream = AudioSystem.getAudioInputStream(fixture!!)
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

    private val expectedPcecC16 = "Weher like in p on tomorrow. Remind me to call them at five."
    private val expectedPlainC16 = "weather like in p on tomorrow remind me to call m at five"
    private val expectedPcecC32 = "Hey gandle, what weather like in Peton tomorrow? Remind me to call them at five."

    @Test
    fun offlinePcecC16MatchesReference() {
        assumeTrue(modelDir != null && fixture != null)
        Asr4allEngine.open(modelDir!!, "stream_c16r4").use { engine ->
            assertEquals(expectedPcecC16, engine.decodeOffline(clip(), applyPcec = true))
        }
    }

    @Test
    fun offlinePlainC16MatchesReference() {
        assumeTrue(modelDir != null && fixture != null)
        Asr4allEngine.open(modelDir!!, "stream_c16r4").use { engine ->
            assertEquals(expectedPlainC16, engine.decodeOffline(clip(), applyPcec = false))
        }
    }

    @Test
    fun offlinePcecC32MatchesReference() {
        assumeTrue(modelDir != null && fixture != null)
        Asr4allEngine.open(modelDir!!, "stream_c32r8").use { engine ->
            assertEquals(expectedPcecC32, engine.decodeOffline(clip(), applyPcec = true))
        }
    }

    @Test
    fun chunkedStreamingMatchesReferenceStreamLoop() {
        assumeTrue(modelDir != null && fixture != null)
        val samples = clip()
        Asr4allEngine.open(modelDir!!, "stream_c16r4").use { engine ->
            var partial = ""
            var i = 0
            while (i < samples.size) {
                val end = minOf(samples.size, i + 8000) // 0.5 s, like the reference --stream loop
                partial = engine.decodeStream(samples.copyOfRange(i, end))
                i = end
            }
            engine.finish()
            assertEquals(expectedPcecC16, engine.decodePcec(decodeTail = true))
            assertTrue(partial.isNotEmpty())
        }
    }

    @Test
    fun cancelMidUtteranceThenRestartMatchesFreshDecode() {
        assumeTrue(modelDir != null && fixture != null)
        val samples = clip()
        Asr4allEngine.open(modelDir!!, "stream_c16r4").use { engine ->
            engine.decodeStream(samples.copyOfRange(0, samples.size / 2))
            engine.reset() // cancel: caches, PCM and PCEC state all cleared
            assertEquals(expectedPcecC16, engine.decodeOffline(samples, applyPcec = true))
        }
    }

    @Test
    fun closedEngineRejectsUseAndReopenWorks() {
        assumeTrue(modelDir != null && fixture != null)
        val samples = clip()
        repeat(3) {
            val engine = Asr4allEngine.open(modelDir!!, "stream_c16r4")
            assertEquals(expectedPcecC16, engine.decodeOffline(samples, applyPcec = true))
            engine.close()
            assertTrue(engine.isClosed)
            assertThrows(IllegalStateException::class.java) { engine.decodeStream(FloatArray(16)) }
            engine.close() // idempotent
        }
    }
}
