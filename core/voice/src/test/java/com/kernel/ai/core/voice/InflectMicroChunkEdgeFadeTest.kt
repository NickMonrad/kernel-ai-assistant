package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InflectMicroChunkEdgeFadeTest {
    @Test
    fun fadeChunkEdges_silences_both_edges_and_leaves_the_middle_untouched() {
        val samples = FloatArray(1_000) { 1.0f }

        val faded = fadeChunkEdges(samples, sampleRate = 24_000)

        assertEquals(0.0f, faded.first())
        assertEquals(0.0f, faded.last())
        assertEquals(1.0f, faded[500])
    }

    @Test
    fun fadeChunkEdges_ramps_linearly_over_five_milliseconds() {
        val samples = FloatArray(10_000) { 1.0f }

        fadeChunkEdges(samples, sampleRate = 24_000)

        // 5 ms at 24 kHz is 120 frames; the ramp reaches full level at frame 120.
        assertEquals(60.0f / 120.0f, samples[60], 1e-6f)
        assertEquals(1.0f, samples[120])
    }
}
