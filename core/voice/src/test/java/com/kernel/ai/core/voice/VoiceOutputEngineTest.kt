package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceOutputEngineTest {
    @Test
    fun `release eligible high-memory catalogue includes Inflect`() {
        assertTrue(
            VoiceOutputEngine.entriesForBuild(
                isRelease = true,
                inflectEligible = true,
            ).contains(VoiceOutputEngine.InflectMicroExperimental),
        )
    }

    @Test
    fun `release ineligible catalogue excludes Inflect`() {
        assertFalse(
            VoiceOutputEngine.entriesForBuild(
                isRelease = true,
                inflectEligible = false,
            ).contains(VoiceOutputEngine.InflectMicroExperimental),
        )
    }

    @Test
    fun `release catalogue excludes Kokoro and other debug engines`() {
        val engines = VoiceOutputEngine.entriesForBuild(
            isRelease = true,
            inflectEligible = true,
        )

        assertFalse(engines.contains(VoiceOutputEngine.KokoroExperimental))
    }

    @Test
    fun `debug catalogue retains explicit experimental engines`() {
        val engines = VoiceOutputEngine.entriesForBuild(
            isRelease = false,
            inflectEligible = false,
        )

        assertTrue(engines.contains(VoiceOutputEngine.KokoroExperimental))
        assertTrue(engines.contains(VoiceOutputEngine.InflectMicroExperimental))
    }
}
