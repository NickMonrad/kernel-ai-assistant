package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroSpeedTestTest {
    private val sampleRate = 24_000

    @Test
    fun real_time_factor_is_synthesis_time_over_audio_length() {
        // Each chunk is one second of audio and takes half a second to make, so the factor is 0.5.
        var now = 0L
        val result = measureKokoroSpeed(
            text = "one. two.",
            splitText = { listOf("one.", "two.") },
            synthesizeChunk = {
                now += 500_000_000L
                FloatArray(sampleRate)
            },
            sampleRate = sampleRate,
            peakMemoryMb = { 1_300L },
            nanoTime = { now },
        ) as KokoroSpeedResult.Measured

        assertEquals(2.0, result.audioSeconds, 1e-9)
        assertEquals(1.0, result.synthesisSeconds, 1e-9)
        assertEquals(0.5, result.realTimeFactor, 1e-9)
        assertEquals(0.5, result.firstChunkSeconds, 1e-9)
        assertEquals(1_300L, result.peakMemoryMb)
        assertTrue(result.allowsKokoro)
    }

    @Test
    fun median_rtf_uses_the_middle_run_for_an_odd_count() {
        val result = aggregateKokoroSpeedRuns(
            listOf(run(0.5), run(0.9), run(1.2)),
            peakMemoryMb = null,
        ) as KokoroSpeedResult.Measured
        assertEquals(0.9, result.medianFirstChunkSeconds, 1e-9)
    }

    @Test
    fun median_rtf_averages_the_middle_runs_for_an_even_count() {
        val result = aggregateKokoroSpeedRuns(
            listOf(run(0.4), run(0.6), run(1.0), run(1.2)),
            peakMemoryMb = null,
        ) as KokoroSpeedResult.Measured
        assertEquals(0.8, result.medianRealTimeFactor, 1e-9)
    }

    @Test
    fun phone_at_the_selection_limit_is_allowed_but_above_it_is_blocked() {
        assertTrue((aggregateKokoroSpeedRuns(listOf(run(0.9)), null) as KokoroSpeedResult.Measured).allowsKokoro)
        assertFalse((aggregateKokoroSpeedRuns(listOf(run(0.91)), null) as KokoroSpeedResult.Measured).allowsKokoro)
    }

    private fun run(rtf: Double) = KokoroSpeedResult.Measured.Run(
        audioSeconds = 1.0,
        synthesisSeconds = rtf,
        firstChunkSeconds = rtf,
    )

    @Test
    fun a_run_that_makes_no_audio_is_unavailable_not_a_zero_speed() {
        val result = measureKokoroSpeed(
            text = "",
            splitText = { emptyList() },
            synthesizeChunk = { FloatArray(0) },
            sampleRate = sampleRate,
            peakMemoryMb = { null },
            nanoTime = { 0L },
        )
        assertTrue(result is KokoroSpeedResult.Unavailable)
    }

    @Test
    fun peak_memory_is_read_from_vmhwm_in_megabytes() {
        val status = "Name:\tkernel\nVmPeak:\t 2000000 kB\nVmHWM:\t    1048576 kB\nVmRSS:\t 900000 kB\n"
        assertEquals(1024L, parseVmHwmMb(status))
    }

    @Test
    fun missing_vmhwm_gives_no_memory_figure() {
        assertNull(parseVmHwmMb("Name:\tkernel\nVmRSS:\t 900000 kB\n"))
    }
}
