package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoiceOutputPreferencesTest {

    @Test
    fun `resolveReleaseBuildVoice maps Semaine to CoriHigh in release build`() {
        assertEquals(
            SherpaPiperVoice.CoriHigh,
            VoiceOutputPreferences.resolveReleaseBuildVoice(
                SherpaPiperVoice.SemaineMedium,
                isReleaseBuild = true,
            ),
        )
    }

    @Test
    fun `resolveReleaseBuildVoice leaves CoriHigh unchanged in release build`() {
        assertEquals(
            SherpaPiperVoice.CoriHigh,
            VoiceOutputPreferences.resolveReleaseBuildVoice(
                SherpaPiperVoice.CoriHigh,
                isReleaseBuild = true,
            ),
        )
    }

    @Test
    fun `resolveReleaseBuildVoice leaves Semaine unchanged in debug build`() {
        assertEquals(
            SherpaPiperVoice.SemaineMedium,
            VoiceOutputPreferences.resolveReleaseBuildVoice(
                SherpaPiperVoice.SemaineMedium,
                isReleaseBuild = false,
            ),
        )
    }

    @Test
    fun `resolveReleaseBuildVoice leaves Jenny unchanged in release build`() {
        assertEquals(
            SherpaPiperVoice.JennyDioco,
            VoiceOutputPreferences.resolveReleaseBuildVoice(
                SherpaPiperVoice.JennyDioco,
                isReleaseBuild = true,
            ),
        )
    }
}
