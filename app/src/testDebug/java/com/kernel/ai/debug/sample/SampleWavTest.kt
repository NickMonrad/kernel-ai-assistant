package com.kernel.ai.debug.sample

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SampleWavTest {
    @Test
    fun `gain multiplies and clamps to unit range like playback`() {
        val out = SampleWav.applyGain(floatArrayOf(0.5f, -0.5f, 0.9f), 1.5f)
        assertArrayEquals(floatArrayOf(0.75f, -0.75f, 1.0f), out, 1e-6f)
    }

    @Test
    fun `wav data chunk holds exactly one 16-bit frame per sample at the given rate`() {
        val samples = FloatArray(13568) { 0.25f }
        val bytes = SampleWav.wavBytes(samples, 22050)

        assertEquals(44 + samples.size * 2, bytes.size)
        val dataSize = (bytes[40].toInt() and 0xff) or ((bytes[41].toInt() and 0xff) shl 8) or
            ((bytes[42].toInt() and 0xff) shl 16) or ((bytes[43].toInt() and 0xff) shl 24)
        assertEquals(samples.size * 2, dataSize)
        val rate = (bytes[24].toInt() and 0xff) or ((bytes[25].toInt() and 0xff) shl 8) or
            ((bytes[26].toInt() and 0xff) shl 16) or ((bytes[27].toInt() and 0xff) shl 24)
        assertEquals(22050, rate)
    }
}
