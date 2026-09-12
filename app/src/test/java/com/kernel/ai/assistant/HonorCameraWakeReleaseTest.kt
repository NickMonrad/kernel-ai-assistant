package com.kernel.ai.assistant

import com.kernel.ai.core.voice.CameraForegroundMonitor
import com.kernel.ai.core.voice.ForegroundEventSource
import com.kernel.ai.core.voice.ForegroundPackageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1502: yielding the microphone to the Honor Camera must latch the suspension before any capture
 * is released. A re-arm path (voice-session event, wake-command handoff terminal, debug resume
 * hook) that ran in between would hand the microphone straight back to Jandal and the camera would
 * fail to record — the failure mode the feasibility spike hit.
 *
 * The same release is the fail-safe path taken when the camera-coexistence capability is lost, and
 * there the ordering is stricter still: the service may stop only after every capture was actually
 * released, because the stop cancels the monitor that retries the release.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `a failed release does not stop the service`() {
        var stopped = false

        assertThrows(IllegalStateException::class.java) {
            releaseWakeCaptureThenStop(
                suspendLatch = {},
                releaseWakeDetector = { throw IllegalStateException("AudioRecord release failed") },
                releaseVoiceCapture = {},
                stopService = { stopped = true },
            )
        }

        // Stopping the service cancels the monitor that owns the retry cadence, so a failed
        // release must leave it running: the microphone may still be held, and only the monitor
        // can ask for it back. The failure is reported instead — see the lifecycle regression.
        assertFalse(stopped, "a failed release must not stop the service")
    }

    @Test
    fun `a successful release stops the service without reporting a failure`() {
        var stopped = false

        releaseWakeCaptureThenStop(
            suspendLatch = {},
            releaseWakeDetector = {},
            releaseVoiceCapture = {},
            stopService = { stopped = true },
        )

        assertTrue(stopped)
    }

    /**
     * The production lifecycle: the monitor runs inside the service scope and the service stop
     * cancels that scope, so the monitor can only retry a failed cleanup while the service is
     * still alive. This wires the real monitor to the real service-side cleanup and drives the
     * sequence `fail -> remain alive -> retry -> succeed -> stop`.
     */
    @Test
    fun `a failed cleanup leaves the service running so the monitor retries it to completion`() =
        runTest {
            val serviceScope =
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var usageAccess = true
            val latches = mutableListOf<String>()
            var detectorReleases = 0
            var voiceReleases = 0
            var detectorFailures = 1
            var stops = 0
            val monitor = CameraForegroundMonitor(
                source = ForegroundEventSource { _, _ -> emptyList() },
                targetPackage = "com.hihonor.camera",
                pollIntervalMs = 1_000,
                currentForegroundPackage = ForegroundPackageSource { "com.hihonor.android.launcher" },
                isReady = { usageAccess },
                onCameraEntered = {},
                onCameraExited = {},
                onUnavailable = {
                    releaseWakeCaptureThenStop(
                        suspendLatch = { latches += "attempt ${detectorReleases + 1}" },
                        releaseWakeDetector = {
                            detectorReleases++
                            if (detectorFailures-- > 0) {
                                throw IllegalStateException("AudioRecord release failed")
                            }
                        },
                        releaseVoiceCapture = { voiceReleases++ },
                        stopService = {
                            stops++
                            serviceScope.cancel()
                        },
                    )
                },
            )
            val monitorJob = serviceScope.launch { monitor.run() }
            runCurrent()

            usageAccess = false
            advanceTimeBy(1_001)
            runCurrent()

            // First attempt: suspended, both captures attempted, one failed.
            assertEquals(listOf("attempt 1"), latches)
            assertEquals(1, detectorReleases)
            assertEquals(1, voiceReleases, "the second release is attempted even when the first fails")
            assertEquals(0, stops, "a failed release must not stop the service")
            assertTrue(serviceScope.isActive, "the monitor has to survive to retry the cleanup")
            assertFalse(monitorJob.isCancelled)

            // Next cadence retries the whole cleanup, which now succeeds.
            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(listOf("attempt 1", "attempt 2"), latches)
            assertEquals(2, detectorReleases)
            assertEquals(2, voiceReleases)
            assertEquals(1, stops, "the service stops only once the release completed")
            assertFalse(serviceScope.isActive, "stopping the service cancels the monitor's scope")

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(2, detectorReleases, "monitoring ends after the cleanup succeeds")
            serviceScope.cancel()
        }
}
