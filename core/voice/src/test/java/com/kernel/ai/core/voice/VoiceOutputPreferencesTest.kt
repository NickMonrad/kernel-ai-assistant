package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.hardware.HardwareTier
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

    @Test
    fun `resolveForBuild preserves Inflect for eligible release device`() {
        val inflectEligible = InflectMicroModelSpec.isReleaseEligible(
            tier = HardwareTier.FLAGSHIP,
            supportedAbis = arrayOf("arm64-v8a"),
        )
        assertEquals(
            VoiceOutputEngine.InflectMicroExperimental,
            VoiceOutputEngine.resolveForBuild(
                value = VoiceOutputEngine.InflectMicroExperimental.name,
                isRelease = true,
                inflectEligible = inflectEligible,
            ),
        )
    }

    @Test
    fun `resolveForBuild demotes persisted Inflect on flagship wrong ABI`() {
        val inflectEligible = InflectMicroModelSpec.isReleaseEligible(
            tier = HardwareTier.FLAGSHIP,
            supportedAbis = arrayOf("x86_64"),
        )
        assertEquals(
            VoiceOutputEngine.AndroidTts,
            VoiceOutputEngine.resolveForBuild(
                value = VoiceOutputEngine.InflectMicroExperimental.name,
                isRelease = true,
                inflectEligible = inflectEligible,
            ),
        )
    }

    @Test
    fun `resolveForBuild demotes persisted Kokoro in release`() {
        assertEquals(
            VoiceOutputEngine.AndroidTts,
            VoiceOutputEngine.resolveForBuild(
                value = VoiceOutputEngine.KokoroExperimental.name,
                isRelease = true,
                inflectEligible = true,
            ),
        )
    }
}
