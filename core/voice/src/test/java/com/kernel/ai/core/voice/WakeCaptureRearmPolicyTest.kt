package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1502: every condition that must still hold before Jandal takes the microphone back after the
 * Honor Camera leaves the foreground.
 */
class WakeCaptureRearmPolicyTest {

    private fun mayRearm(
        heyJandalEnabled: Boolean = true,
        recordAudioGranted: Boolean = true,
        captureSuspended: Boolean = false,
        voiceSessionActive: Boolean = false,
        captureAllowed: Boolean = true,
    ) = mayRearmAfterCamera(
        heyJandalEnabled = heyJandalEnabled,
        recordAudioGranted = recordAudioGranted,
        captureSuspended = captureSuspended,
        voiceSessionActive = voiceSessionActive,
        captureAllowed = captureAllowed,
    )

    @Test
    fun `re-arms when every condition still holds`() {
        assertTrue(mayRearm())
    }

    @Test
    fun `does not re-arm when Hey Jandal was disabled while the camera was foreground`() {
        assertFalse(mayRearm(heyJandalEnabled = false))
    }

    @Test
    fun `does not re-arm when the microphone permission was removed while suspended`() {
        assertFalse(mayRearm(recordAudioGranted = false))
    }

    @Test
    fun `does not re-arm while Usage Access is gone on the affected device`() {
        assertFalse(mayRearm(captureAllowed = false))
    }

    @Test
    fun `does not re-arm while the camera still holds the microphone`() {
        assertFalse(mayRearm(captureSuspended = true))
    }

    @Test
    fun `does not re-arm into an active Jandal voice session`() {
        assertFalse(mayRearm(voiceSessionActive = true))
    }
}
