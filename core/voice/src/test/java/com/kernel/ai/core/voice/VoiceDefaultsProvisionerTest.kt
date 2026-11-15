package com.kernel.ai.core.voice

import com.kernel.ai.core.voice.VoiceDefaultsProvisioner.Companion.planSpeechOutputVoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoiceDefaultsProvisionerTest {
    @Test
    fun `updated install without a stored engine keeps Android TTS and starts nothing`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = false,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(true, plan.keepAndroidTts)
        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `fresh install whose effective engine is Piper starts the missing voice`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = false,
            isExistingInstall = false,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }

    @Test
    fun `effective Android TTS never starts the Piper voice`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = false,
            effectiveEngine = VoiceOutputEngine.AndroidTts,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `stored Piper with the voice already downloaded starts nothing`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = true,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `stored Piper with a missing voice starts it even on an updated install`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }

    @Test
    fun `engine picked during the consent wait is not overwritten by the updated-install default`() {
        // The caller reads hasStoredEngine after the wait, so a Piper pick made during it is stored by then.
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }
}
