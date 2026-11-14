package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceDefaultsProvisionerTest {
    @Test
    fun `fresh install whose effective engine is Piper queues the missing voice`() {
        assertTrue(
            VoiceDefaultsProvisioner.shouldQueueSpeechOutputVoice(
                engine = VoiceOutputEngine.SherpaExperimental,
                voiceDownloaded = false,
            ),
        )
    }

    @Test
    fun `effective Android TTS never queues the Piper voice`() {
        assertFalse(
            VoiceDefaultsProvisioner.shouldQueueSpeechOutputVoice(
                engine = VoiceOutputEngine.AndroidTts,
                voiceDownloaded = false,
            ),
        )
    }

    @Test
    fun `an already downloaded Piper voice is not queued again`() {
        assertFalse(
            VoiceDefaultsProvisioner.shouldQueueSpeechOutputVoice(
                engine = VoiceOutputEngine.SherpaExperimental,
                voiceDownloaded = true,
            ),
        )
    }
}
