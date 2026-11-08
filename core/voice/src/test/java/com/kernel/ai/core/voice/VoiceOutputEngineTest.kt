package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.hardware.HardwareTier
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
    fun `flagship wrong ABI catalogue excludes Inflect`() {
        val inflectEligible = InflectMicroModelSpec.isReleaseEligible(
            tier = HardwareTier.FLAGSHIP,
            supportedAbis = arrayOf("x86_64"),
        )
        assertFalse(
            VoiceOutputEngine.entriesForBuild(
                isRelease = true,
                inflectEligible = inflectEligible,
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

    @Test
    fun `flagship arm64 support is release eligible`() {
        assertTrue(
            InflectMicroModelSpec.isReleaseEligible(
                tier = HardwareTier.FLAGSHIP,
                supportedAbis = arrayOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun `flagship non-arm64 support is not release eligible`() {
        assertFalse(
            InflectMicroModelSpec.isReleaseEligible(
                tier = HardwareTier.FLAGSHIP,
                supportedAbis = arrayOf("x86_64"),
            ),
        )
    }

    @Test
    fun `mid-range arm64 support is not release eligible`() {
        assertFalse(
            InflectMicroModelSpec.isReleaseEligible(
                tier = HardwareTier.MID_RANGE,
                supportedAbis = arrayOf("arm64-v8a"),
            ),
        )
    }
}
