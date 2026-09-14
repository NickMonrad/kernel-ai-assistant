package com.kernel.ai.core.voice

import android.content.Context
import io.mockk.mockk
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

    /**
     * Current foreground state as the monitor's [ForegroundPackageSource] reports it. A null
     * response stands for a lookback that contains no foreground activity at all; an unreadable
     * source throws instead, which is what a revoked or failing query looks like.
     */
    private class FakeCurrent(
        private val responses: List<String?>,
        private val unreadable: Boolean = false,
    ) : ForegroundPackageSource {
        var queries = 0
            private set

        override fun currentForegroundPackage(): String? {
            val response = responses[queries.coerceAtMost(responses.size - 1)]
            queries += 1
            if (unreadable && response == null) {
                throw IllegalStateException("foreground events unavailable")
            }
            return response
        }

        companion object {
            fun of(vararg responses: String?): FakeCurrent = FakeCurrent(responses.toList())

            /** Every null response throws, standing for a state that cannot be read at all. */
            fun unreadable(vararg responses: String?): FakeCurrent =
                FakeCurrent(responses.toList(), unreadable = true)
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
        current: ForegroundPackageSource = FakeCurrent.of(otherApp),
        isReady: () -> Boolean = { true },
        onCameraEntered: suspend () -> Unit = { recorder.entered += 1 },
        onCameraExited: suspend () -> Unit = { recorder.exited += 1 },
        onUnavailable: suspend () -> Unit = { recorder.unavailable += 1 },
        clock: () -> Long = { NOW },
    ) = CameraForegroundMonitor(
        source = source,
        targetPackage = camera,
        pollIntervalMs = pollIntervalMs,
        currentForegroundPackage = current,
        isReady = isReady,
        onCameraEntered = onCameraEntered,
        onCameraExited = onCameraExited,
        onUnavailable = onUnavailable,
        clock = clock,
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
        assertEquals(1, recorder.exited, "the startup state alone is already a clean, armable state")
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
        assertEquals(2, recorder.exited, "the startup report, then the camera leaving")
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
        assertEquals(2, recorder.exited, "the startup report, then the camera leaving")
        job.cancel()
    }

    // ── Startup state: a service or process restart must not take the microphone first ─────────

    @Test
    fun `a camera in front before the monitor started keeps wake capture suspended until it leaves`() =
        runTest {
            // The camera has been foreground since well before this monitor existed, so its launch
            // is in none of the polls' windows — the state can only come from the lookback the
            // current-foreground source replays.
            val source = FakeSource.of(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(exit(camera)),
            )
            val recorder = Recorder()
            var armed = false
            val job = launch {
                monitor(
                    source = source,
                    recorder = recorder,
                    current = FakeCurrent.of(camera),
                    onCameraExited = { armed = true },
                ).run()
            }

            runCurrent()
            assertEquals(1, recorder.entered, "the startup state suspends capture before anything arms")
            assertEquals(0, source.queries, "no event window is consulted before the state is known")

            advanceTimeBy(pollIntervalMs * 3)
            runCurrent()
            assertFalse(armed, "no poll may arm capture while the camera is still in front")

            advanceTimeBy(pollIntervalMs * 6)
            runCurrent()
            assertTrue(armed, "the camera leaving is what releases capture to arm")
            job.cancel()
        }

    @Test
    fun `a clean startup reports the state so the caller may arm`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()

        assertEquals(1, recorder.exited, "the caller arms from the startup report, not from a window")
        assertEquals(0, recorder.entered)
        job.cancel()
    }

    @Test
    fun `a startup whose lookback holds no foreground activity arms`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        val current = FakeCurrent.of(null)
        val job = launch { monitor(source, recorder, current = current).run() }

        runCurrent()

        assertEquals(
            1,
            recorder.exited,
            "an empty lookback means the camera produced no event and is not in front",
        )
        assertEquals(0, recorder.entered)
        job.cancel()
    }

    @Test
    fun `the current package is replayed from the events that are still readable`() {
        var windowStart = -1L
        var windowEnd = -1L
        val source = UsageStatsCurrentForegroundSource(
            context = mockk(relaxed = true),
            lookbackMillis = SEED_LOOKBACK_MS,
            clock = { NOW },
            eventSource = {
                ForegroundEventSource { start, end ->
                    windowStart = start
                    windowEnd = end
                    listOf(enter(otherApp), enter(camera))
                }
            },
        )

        assertEquals(
            camera,
            source.currentForegroundPackage(),
            "a camera that resumed earlier and never left is still the package in front",
        )
        assertEquals(NOW, windowEnd, "the window always ends at the moment of the query")
        assertTrue(
            windowStart <= NOW - 60L * 60 * 1000,
            "the window has to outlast a camera session that started hours earlier, not a poll; " +
                "it was only ${NOW - windowStart}ms",
        )
    }

    @Test
    fun `an unreadable startup state is retried and arms nothing until it can be read`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        val current = FakeCurrent.unreadable(null, null, otherApp)
        val job = launch { monitor(source, recorder, current = current).run() }

        runCurrent()
        assertEquals(0, recorder.exited, "an unanswerable state must not arm wake capture")
        assertEquals(0, source.queries, "and must not be patched up from a stale window")

        advanceTimeBy(pollIntervalMs * 4)
        runCurrent()

        assertTrue(current.queries >= 3, "the state is re-read on every cadence until it is known")
        assertEquals(1, recorder.exited, "the first answerable state is what arms, exactly once")
        assertTrue(source.queries <= 2, "event polling starts only once the state is known")
        job.cancel()
    }

    // ── Failure handling ──────────────────────────────────────────────────────────────────────

    @Test
    fun `later polls read only the window since the state was resolved`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        val job = launch { monitor(source, recorder).run() }

        runCurrent()
        advanceTimeBy(pollIntervalMs * 3)
        runCurrent()

        assertTrue(source.windows.isNotEmpty())
        assertEquals(
            List(source.windows.size) { NOW to NOW },
            source.windows,
            "no poll reaches back before the moment the state was resolved",
        )
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
    fun `a failed unavailable cleanup is retried instead of dropping the fail-safe`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        var ready = true
        var attempts = 0
        val job = launch {
            monitor(
                source = source,
                recorder = recorder,
                isReady = { ready },
                onUnavailable = {
                    attempts += 1
                    if (attempts < 3) throw IllegalStateException("release failed")
                    recorder.unavailable += 1
                },
            ).run()
        }

        runCurrent()
        ready = false
        advanceTimeBy(pollIntervalMs * 2)
        runCurrent()
        assertTrue(attempts >= 1, "the cleanup is attempted as soon as the capability is gone")
        assertFalse(job.isCompleted, "a failed cleanup must not end monitoring")

        advanceTimeBy(pollIntervalMs * 4)
        runCurrent()

        assertTrue(attempts >= 3, "the cleanup is retried on the next cadence until it succeeds")
        assertEquals(1, recorder.unavailable, "cleanup is reported once, when it succeeds")
        assertTrue(job.isCompleted, "monitoring ends once the cleanup has succeeded")
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
    fun `cancellation is not swallowed by the transition callback`() = runTest {
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

    @Test
    fun `cancellation is not swallowed while cleaning up after lost usage access`() = runTest {
        val source = FakeSource.of(emptyList())
        val recorder = Recorder()
        var ready = true
        val job = launch {
            monitor(
                source = source,
                recorder = recorder,
                isReady = { ready },
                onUnavailable = { throw CancellationException("cancelled") },
            ).run()
        }

        runCurrent()
        ready = false
        advanceTimeBy(pollIntervalMs)
        runCurrent()

        assertTrue(job.isCancelled, "CancellationException must propagate out of the cleanup")
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
