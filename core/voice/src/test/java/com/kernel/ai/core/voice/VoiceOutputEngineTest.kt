package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.kokoro.KokoroSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceOutputEngineTest {
    @Test
    fun `Kokoro Settings copy identifies it as experimental`() {
        assertEquals("Kokoro (experimental)", VoiceOutputEngine.KokoroExperimental.displayName)
        assertTrue(VoiceOutputCopy.body(VoiceOutputEngine.KokoroExperimental).contains("Kokoro-82M"))
    }

    @Test
    fun `Kokoro card description quotes the same pack size as the chip`() {
        assertTrue(VoiceOutputCopy.body(VoiceOutputEngine.KokoroExperimental).contains("about ${KokoroSetup.approxSizeText()};"))
    }

    @Test
    fun `pack size rounds decimal megabytes`() {
        assertEquals("348 MB", KokoroSetup.formatSizeMb(348_400_000L))
        assertEquals("351 MB", KokoroSetup.formatSizeMb(350_600_000L))
    }
    @Test
    fun `default engine is Piper on mid-range and flagship devices and Android TTS on low-power`() {
        assertEquals(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.defaultForTier(HardwareTier.FLAGSHIP))
        assertEquals(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.defaultForTier(HardwareTier.MID_RANGE))
        assertEquals(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.defaultForTier(HardwareTier.LOW_POWER))
    }

    @Test
    fun `release resolves an unavailable default to Android TTS while a stored Android choice is kept`() {
        assertEquals(
            VoiceOutputEngine.AndroidTts,
            VoiceOutputEngine.resolveForBuild(null, isRelease = true, inflectEligible = false, default = VoiceOutputEngine.InflectMicroExperimental),
        )
        assertEquals(
            VoiceOutputEngine.AndroidTts,
            VoiceOutputEngine.resolveForBuild("AndroidTts", isRelease = true, inflectEligible = false, default = VoiceOutputEngine.InflectMicroExperimental),
        )
    }

    @Test
    fun `release catalogue offers Piper, Kokoro, Inflect on eligible devices, and Android TTS`() {
        assertEquals(
            listOf(
                VoiceOutputEngine.AndroidTts,
                VoiceOutputEngine.SherpaExperimental,
                VoiceOutputEngine.KokoroExperimental,
                VoiceOutputEngine.InflectMicroExperimental,
            ),
            VoiceOutputEngine.entriesForBuild(
                isRelease = true,
                inflectEligible = true,
            ),
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
    fun `release catalogue includes Kokoro`() {
        val engines = VoiceOutputEngine.entriesForBuild(
            isRelease = true,
            inflectEligible = true,
        )

        assertTrue(engines.contains(VoiceOutputEngine.KokoroExperimental))
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
