package com.kernel.ai.core.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SileroVadTest {
    private fun shippedModel(): SileroVad = SileroVad.fromBytes(File("src/vad/assets/silero_vad.onnx").readBytes())

    @Test
    fun `silence is not speech`() {
        val vad = shippedModel()
        val silence = ShortArray(16_000)
        assertFalse(vad.isSpeech(silence, silence.size))
    }

    @Test
    fun `steady hiss at fan level is not speech`() {
        val vad = shippedModel()
        val rng = Random(1)
        // Uniform hiss at about 5% of full scale for one second: the fan/TV-like floor from #2078.
        val hiss = ShortArray(16_000) { rng.nextInt(-1638, 1639).toShort() }
        assertFalse(vad.isSpeech(hiss, hiss.size))
    }

    /**
     * Replay of the reference recording (16 kHz mono PCM): the endpoint must come after the last speech, not cut the
     * utterance short, and within a second of it (the 700 ms trailing silence plus one chunk).
     */
    @Test
    fun `reference recording ends after its last speech and within a second of it`() {
        val pcm = readPcm16(REFERENCE_WAV)
        assertReplayEndsAfterSpeech(pcm)
    }

    /** The same recording at -26 dB (the reviewer's quiet-speaker case): it must still arm and end after its speech. */
    @Test
    fun `quiet recording at minus 26 dB still ends after its last speech`() {
        val gain = 10f.pow(-26f / 20f)
        val pcm = readPcm16(REFERENCE_WAV)
        val quiet = ShortArray(pcm.size) { (pcm[it] * gain).roundToInt().toShort() }
        assertReplayEndsAfterSpeech(quiet)
    }

    /** A 100 ms start cue (400 Hz + 1200 Hz) followed by silence must not arm the endpoint, so it cannot end the capture. */
    @Test
    fun `a 100 ms tone does not end the capture by VAD`() {
        val cue = ShortArray(1_600) { i ->
            val t = i / 16_000.0
            (0.5 * 32_767 * (sin(2 * PI * 400 * t) + sin(2 * PI * 1_200 * t)) / 2).toInt().toShort()
        }
        val silence = ShortArray(24_000)
        val chunks = speechChunks(cue + silence)
        val (reason, _) = endpoint(chunks)
        assertNotEquals(EndpointReason.VAD, reason)
    }

    /**
     * A 100 ms start cue (400 Hz + 1200 Hz) at 150-300 ms over a -55 dBFS room floor. Its speech-like tail must not arm
     * the endpoint inside the 500 ms start guard, so the capture runs to the no-speech window. Without the guard the same
     * stimulus must arm and end by VAD; that side of the assertion proves the stimulus can fail this test.
     */
    @Test
    fun `a start cue over a minus 55 dB floor does not arm the endpoint inside the 500 ms guard`() {
        val rng = java.util.Random(7)
        val sigma = 32_767 * Math.pow(10.0, -55 / 20.0)
        val guardSamples = (START_CUE_GUARD_MS * 16).toInt()
        val unguardedReasons = mutableListOf<EndpointReason?>()
        for (cueStartMs in listOf(150, 200, 250, 300)) {
            val pcm = ShortArray(11 * 16_000) { (rng.nextGaussian() * sigma).toInt().toShort() }
            val cueAt = cueStartMs * 16
            for (i in 0 until 1_600) {
                val t = i / 16_000.0
                val tone = 0.5 * 32_767 * (sin(2 * PI * 400 * t) + sin(2 * PI * 1_200 * t)) / 2
                pcm[cueAt + i] = (pcm[cueAt + i] + tone).roundToInt().coerceIn(-32_768, 32_767).toShort()
            }
            val chunks = speechChunks(pcm)
            assertEquals(
                EndpointReason.NO_SPEECH,
                endpoint(chunks, cueGuardSamples = guardSamples).first,
                "cue at ${cueStartMs}ms armed the endpoint inside the guard",
            )
            unguardedReasons += endpoint(chunks).first
        }
        assertTrue(
            EndpointReason.VAD in unguardedReasons,
            "the stimulus must arm without the guard, or this test proves nothing: $unguardedReasons",
        )
    }

    private fun assertReplayEndsAfterSpeech(pcm: ShortArray) {
        // A recording may end mid-silence or right after its speech, so pad 1.5 s of silence: the endpoint must fall
        // inside that padding, not at the end of the file.
        val chunks = speechChunks(pcm + ShortArray(24_000))
        val lastSpeechMs = lastSpeechEndMs(chunks)
        val (reason, endMs) = endpoint(chunks)
        assertEquals(EndpointReason.VAD, reason)
        assertTrue(endMs >= lastSpeechMs, "endpoint at ${endMs}ms before last speech at ${lastSpeechMs}ms")
        assertTrue(endMs <= lastSpeechMs + 1_000, "endpoint at ${endMs}ms more than 1 s after last speech at ${lastSpeechMs}ms")
    }

    /** Runs the shipped model over 100 ms chunks: (chunk samples, speech verdict) per chunk. */
    private fun speechChunks(pcm: ShortArray): List<Pair<Int, Boolean>> {
        val vad = shippedModel()
        return (0 until pcm.size step CHUNK).map { start ->
            val n = minOf(CHUNK, pcm.size - start)
            val chunk = pcm.copyOfRange(start, start + n)
            n to vad.isSpeech(chunk, n)
        }
    }

    private fun lastSpeechEndMs(chunks: List<Pair<Int, Boolean>>): Long {
        var samples = 0L
        var last = 0L
        for ((n, speech) in chunks) {
            samples += n
            if (speech) last = samples * 1000L / 16_000L
        }
        return last
    }

    /** The endpointer's decision over the chunk stream, stopping at the first reason: (reason, captured ms there). */
    private fun endpoint(chunks: List<Pair<Int, Boolean>>, cueGuardSamples: Int = 0): Pair<EndpointReason?, Long> {
        val e = UtteranceEndpointer(cueGuardSamples = cueGuardSamples)
        for ((n, speech) in chunks) {
            val reason = e.onChunk(n, speech)
            if (reason != null) return reason to e.capturedMs
        }
        return null to e.capturedMs
    }

    /** Reads the `data` chunk of a 16-bit PCM WAV. */
    private fun readPcm16(path: String): ShortArray {
        val bytes = File(path).readBytes()
        val le = ByteOrder.LITTLE_ENDIAN
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, p + 4, 4).order(le).int
            if (id == "data") {
                val n = minOf(size, bytes.size - p - 8) / 2
                val buf = ByteBuffer.wrap(bytes, p + 8, n * 2).order(le)
                return ShortArray(n) { buf.short }
            }
            p += 8 + size + (size and 1)
        }
        error("no data chunk in $path")
    }

    private companion object {
        const val CHUNK = 1_600 // 100 ms at 16 kHz, the reader's cadence
        const val REFERENCE_WAV = "src/androidTest/assets/asr4all/84-121550-0015.wav"
    }
}

private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
