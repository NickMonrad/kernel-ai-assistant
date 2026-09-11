package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * #1502: yielding the microphone to the Honor Camera must latch the suspension before any capture
 * is released. A re-arm path (voice-session event, wake-command handoff terminal, debug resume
 * hook) that ran in between would hand the microphone straight back to Jandal and the camera would
 * fail to record — the failure mode the feasibility spike hit.
 */
class HonorCameraWakeReleaseTest {

    @Test
    fun `suspension is latched before the wake detector and voice captures are released`() {
        val order = mutableListOf<String>()

        releaseWakeCaptureForCamera(
            suspendLatch = { order += "latch" },
            releaseWakeDetector = { order += "wake-detector" },
            releaseVoiceCapture = { order += "voice-capture" },
        )

        // Both Jandal-owned captures are released, and the latch goes first so no re-arm can slip
        // in while they are being torn down.
        assertEquals(listOf("latch", "wake-detector", "voice-capture"), order)
    }
}
