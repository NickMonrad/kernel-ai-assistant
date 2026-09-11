package com.kernel.ai.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1502: transition behaviour of the Honor camera foreground monitor — the mechanism that decides
 * when Jandal yields the microphone and when it takes it back.
 */
class CameraForegroundMonitorTest {

    private val camera = HonorCameraCoexistence.CAMERA_PACKAGE
    private val otherApp = "com.example.other"
    private val pollIntervalMs = 2_000L
    private val lookbackMs = 60_000L

    /** Foreground source whose responses are scripted per query. */
    private class FakeSource(private val responses: List<() -> List<ForegroundEvent>>) :
        ForegroundEventSource {
        val windows = mutableListOf<Pair<Long, Long>>()
        var queries = 0
            private set

        override fun eventsBetween(startMillis: Long, endMillis: Long): List<ForegroundEvent> {
            val index = queries.coerceAtMost(responses.size - 1)
            queries += 1
            windows += startMillis to endMillis
            return responses[index]()
        }

        companion object {
            fun of(vararg responses: List<ForegroundEvent>): FakeSource =
                FakeSource(responses.map { response -> { response } })

            fun throwingFirst(vararg responses: List<ForegroundEvent>): FakeSource = FakeSource(
                listOf<() -> List<ForegroundEvent>>(
                    { throw IllegalStateException("usage stats unavailable") },
                ) + responses.map { response -> { response } },
            )
        }
    }

    private class Recorder {
        var entered = 0
        var exited = 0
        var unavailable = 0
    }

    private fun monitor(
        source: ForegroundEventSource,
        recorder: Recorder,
        isReady: () -> Boolean = { true },
        onCameraEntered: suspend () -> Unit = { recorder.entered += 1 },
        onCameraExited: suspend () -> Unit = { recorder.exited += 1 },
    ) = CameraForegroundMonitor(
        source = source,
        targetPackage = camera,
        pollIntervalMs = pollIntervalMs,
        initialLookbackMillis = lookbackMs,
        isReady = isReady,
        onCameraEntered = onCameraEntered,
        onCameraExited = onCameraExited,
        onUnavailable = { recorder.unavailable += 1 },
        clock = { NOW },
    )

    private fun enter(packageName: String) = ForegroundEvent(ForegroundTransition.ENTER, packageName)
    private fun exit(packageName: String) = ForegroundEvent(ForegroundTransition.EXIT, packageName)

    @Test
    fun `camera entering the foreground suspends wake capture exactly once`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 5)
        runCurrent()

        assertEquals(1, recorder.entered)
        assertEquals(0, recorder.exited)
        job.cancel()
    }

    @Test
    fun `repeated foreground events do not duplicate the suspend`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 6)
        runCurrent()

        assertEquals(1, recorder.entered, "one suspend for any number of camera foreground events")
        assertTrue(source.queries > 3, "the monitor keeps polling while suspended")
        job.cancel()
    }

    @Test
    fun `camera leaving the foreground re-arms once`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)), listOf(exit(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 3)
        runCurrent()

        assertEquals(1, recorder.entered)
        assertEquals(1, recorder.exited)
        job.cancel()
    }

    @Test
    fun `another app taking the foreground counts as the camera leaving`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)), listOf(enter(otherApp)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 3)
        runCurrent()

        assertEquals(1, recorder.entered)
        assertEquals(1, recorder.exited)
        job.cancel()
    }

    @Test
    fun `a monitor started while the camera is already foreground suspends immediately`() = runTest {
        // The initial lookback window is the only poll that can see an earlier camera launch.
        val source = FakeSource.of(listOf(enter(otherApp), enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()

        assertEquals(1, recorder.entered, "no poll interval may pass before the microphone is released")
        assertEquals(NOW - lookbackMs, source.windows.first().first)
        job.cancel()
    }

    @Test
    fun `later polls read only the window since the previous poll`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 2)
        runCurrent()

        assertEquals(NOW - lookbackMs to NOW, source.windows.first())
        assertEquals(listOf(NOW to NOW, NOW to NOW), source.windows.drop(1))
        job.cancel()
    }

    @Test
    fun `a failing query does not stop monitoring`() = runTest {
        val source = FakeSource.throwingFirst(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 2)
        runCurrent()

        assertEquals(1, recorder.entered)
        job.cancel()
    }

    @Test
    fun `a failing transition callback is retried on the next poll`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        var attempts = 0
        val job = launch {
            monitor(
                source = source,
                recorder = recorder,
                onCameraEntered = {
                    attempts += 1
                    if (attempts == 1) throw IllegalStateException("release failed")
                    recorder.entered += 1
                },
            ).run()
        }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 4)
        runCurrent()

        assertEquals(2, attempts, "the failed release is retried, then stops retrying")
        assertEquals(1, recorder.entered)
        job.cancel()
    }

    @Test
    fun `losing usage access reports once and stops polling`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        var ready = true
        val job = launch { monitor(source, recorder, isReady = { ready }).run() }

        runCurrent()
        ready = false
        advanceTimeBy(pollIntervalMs)
        runCurrent()
        val queriesWhenReported = source.queries
        advanceTimeBy(pollIntervalMs * 5)
        runCurrent()

        assertEquals(1, recorder.unavailable)
        assertEquals(queriesWhenReported, source.queries, "polling stops once the capability is gone")
        job.cancel()
    }

    @Test
    fun `cancelling the monitor stops polling`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs)
        runCurrent()
        val queriesBeforeCancel = source.queries
        job.cancel()
        advanceTimeBy(pollIntervalMs * 5)
        runCurrent()

        assertEquals(queriesBeforeCancel, source.queries)
        assertEquals(1, recorder.entered)
    }

    @Test
    fun `cancellation is not swallowed by the transition guard`() = runTest {
        val source = FakeSource.of(listOf(enter(camera)))
        val recorder = Recorder()
        val job = launch {
            monitor(
                source = source,
                recorder = recorder,
                onCameraEntered = { throw CancellationException("cancelled") },
            ).run()
        }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 3)
        runCurrent()

        assertTrue(job.isCancelled, "CancellationException must propagate out of the monitor")
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }

    // ── Foreground tracking semantics (shared by the monitor and the re-arm guard) ─────────────

    @Test
    fun `the tracker follows the last package that entered the foreground`() {
        val tracker = ForegroundPackageTracker(camera)

        assertFalse(tracker.isTargetForeground)
        assertTrue(tracker.apply(listOf(enter(otherApp), enter(camera))), "entering the target flips state")
        assertTrue(tracker.isTargetForeground)
        assertFalse(tracker.apply(listOf(exit(otherApp))), "a non-target exit does not disturb the target")
        assertTrue(tracker.isTargetForeground)
        assertTrue(tracker.apply(listOf(enter(otherApp))), "another app taking over clears the target")
        assertFalse(tracker.isTargetForeground)
        assertTrue(tracker.apply(listOf(enter(camera))), "re-entering the target flips state back")
        assertFalse(tracker.apply(listOf(enter(camera))), "repeated events are idempotent")
        assertTrue(tracker.isTargetForeground)
    }
}
