package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1502: yielding the microphone to the Honor Camera must latch the suspension before any capture
 * is released. A re-arm path (voice-session event, wake-command handoff terminal, debug resume
 * hook) that ran in between would hand the microphone straight back to Jandal and the camera would
 * fail to record — the failure mode the feasibility spike hit.
 *
 * The release is also the fail-safe path taken when the camera-coexistence capability is lost, so
 * one failing lifecycle call must never skip the remaining cleanup.
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

    @Test
    fun `a failed wake detector release still releases the voice capture and reports the failure`() {
        val order = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            releaseWakeCaptureForCamera(
                suspendLatch = { order += "latch" },
                releaseWakeDetector = {
                    order += "wake-detector"
                    throw IllegalStateException("AudioRecord release failed")
                },
                releaseVoiceCapture = { order += "voice-capture" },
            )
        }

        // A recognizer-held capture left behind would keep blocking the camera on BKQ-N49, so the
        // second release must still be attempted — and the caller must hear about the first, so it
        // can retry the whole release.
        assertEquals(listOf("latch", "wake-detector", "voice-capture"), order)
    }

    @Test
    fun `a failed wake detector release still stops the service`() {
        var stopped = false

        assertThrows(IllegalStateException::class.java) {
            releaseWakeCaptureAndStop(
                releaseCaptures = { throw IllegalStateException("AudioRecord release failed") },
                stopService = { stopped = true },
            )
        }

        // Listening must not continue without the capability that protects the camera, even when a
        // release failed; the exception is reported afterwards so the monitor can retry cleanup.
        assertTrue(stopped, "stopSelf must run even when a release throws")
    }

    @Test
    fun `a successful release stops the service without reporting a failure`() {
        var stopped = false

        releaseWakeCaptureAndStop(
            releaseCaptures = {},
            stopService = { stopped = true },
        )

        assertTrue(stopped)
    }
}
