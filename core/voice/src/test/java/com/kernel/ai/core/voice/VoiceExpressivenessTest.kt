package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceExpressivenessTest {

    @Test
    fun `LOW has correct noise scale values`() {
        assertEquals(0.3f, VoiceExpressiveness.LOW.noiseScale)
        assertEquals(0.3f, VoiceExpressiveness.LOW.noiseScaleW)
    }

    @Test
    fun `MEDIUM has Sherpa default noise scale values`() {
        assertEquals(0.667f, VoiceExpressiveness.MEDIUM.noiseScale)
        assertEquals(0.8f, VoiceExpressiveness.MEDIUM.noiseScaleW)
    }

    @Test
    fun `HIGH has maximum noise scale values`() {
        assertEquals(1.0f, VoiceExpressiveness.HIGH.noiseScale)
        assertEquals(1.0f, VoiceExpressiveness.HIGH.noiseScaleW)
    }

    @Test
    fun `enum has exactly three entries`() {
        assertEquals(3, VoiceExpressiveness.entries.size)
    }

    @Test
    fun `all entries have noise scales in valid range`() {
        VoiceExpressiveness.entries.forEach { level ->
            assertTrue(level.noiseScale in 0.0f..1.0f, "${level.name}.noiseScale out of range")
            assertTrue(level.noiseScaleW in 0.0f..1.0f, "${level.name}.noiseScaleW out of range")
        }
    }

    @Test
    fun `noise scales increase from LOW to HIGH`() {
        assertTrue(VoiceExpressiveness.LOW.noiseScale < VoiceExpressiveness.MEDIUM.noiseScale)
        assertTrue(VoiceExpressiveness.MEDIUM.noiseScale < VoiceExpressiveness.HIGH.noiseScale)
        assertTrue(VoiceExpressiveness.LOW.noiseScaleW < VoiceExpressiveness.MEDIUM.noiseScaleW)
        assertTrue(VoiceExpressiveness.MEDIUM.noiseScaleW < VoiceExpressiveness.HIGH.noiseScaleW)
    }
}
