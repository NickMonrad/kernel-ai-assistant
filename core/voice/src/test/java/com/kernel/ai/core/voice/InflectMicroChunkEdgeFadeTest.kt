package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InflectMicroChunkEdgeFadeTest {
    @Test
    fun chunkEdges_keeps_speech_that_runs_to_the_last_sample_unfaded() {
        // Regression (#2096): a chunk whose last samples are speech must not lose its final phoneme decay.
        val samples = FloatArray(1_000) { 1.0f }

        val shaped = finishChunkEdges(samples, sampleRate = 24_000)

        assertEquals(1.0f, shaped[999])
        assertEquals(1.0f, shaped[500])
    }

    @Test
    fun chunkEdges_append_fifty_milliseconds_of_silence_after_the_speech() {
        val samples = FloatArray(10_000) { 1.0f }

        val shaped = finishChunkEdges(samples, sampleRate = 24_000)

        // 50 ms at 24 kHz is 1200 frames of exact silence after the last speech sample.
        assertEquals(10_000 + 1_200, shaped.size)
        assertEquals(1.0f, shaped[9_999])
        assertEquals(0.0f, shaped[10_000])
        assertEquals(0.0f, shaped[shaped.size - 1])
    }

    @Test
    fun chunkEdges_ramps_the_head_linearly_over_five_milliseconds() {
        val samples = FloatArray(10_000) { 1.0f }

        val shaped = finishChunkEdges(samples, sampleRate = 24_000)

        // 5 ms at 24 kHz is 120 frames; the ramp reaches full level at frame 120.
        assertEquals(60.0f / 120.0f, shaped[60], 1e-6f)
        assertEquals(1.0f, shaped[120])
    }
}
