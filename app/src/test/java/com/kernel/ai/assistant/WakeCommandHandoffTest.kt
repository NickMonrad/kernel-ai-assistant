package com.kernel.ai.assistant

import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.WakeWordDetector
import io.mockk.every
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic tests for the #1433 wake-command handoff
 * ([WakeWordService.runWakeCommandHandoff]).
 *
 * Physical evidence (S21 + S23U diagnostic matrix): the first STT attempt always
 * reached readiness, then the app's 2.5 s alert-session silence watchdog expired it
 * before the runner's command arrived (7.4-9.0 s after readiness), making a retry
 * session the de-facto normal path.  The corrected handoff (1) releases the wake
 * detector's microphone before attempt-1 STT start, and (2) keeps the first session
 * open across the cue-to-command handoff.  The retry remains only for genuine
 * post-readiness failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeCommandHandoffTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class OrderingDetector(private val sharedOrder: MutableList<String>) : WakeWordDetector {
        override val isAvailable: Boolean get() = true
        var released = false

        override fun start(
            generationId: Long,
            onDetected: () -> Unit,
            verifyWindow: ((ShortArray) -> Boolean)?,
        ) {
            sharedOrder += "detector-start"
        }

        override fun stop() {
            sharedOrder += "detector-stop"
            // The strengthened stop() contract: microphone ownership is released
            // before stop() returns.
            released = true
        }
    }

    /** Detector that runs the wake callback on a real thread, like the real one. */
    private class ThreadedDetector : WakeWordDetector {
        override val isAvailable: Boolean get() = true
        @Volatile var released = false
        private var thread: Thread? = null

        override fun start(
            generationId: Long,
            onDetected: () -> Unit,
            verifyWindow: ((ShortArray) -> Boolean)?,
        ) {
            thread = Thread {
                onDetected()
                released = true
            }.also { it.start() }
        }

        override fun stop() {
            // Same contract as OnnxWakeWordDetector.stop() (#1433): return only after
            // the detection thread terminated (mic released), bounded, no self-join.
            val t = thread
            if (t != null && t !== Thread.currentThread() && t.isAlive) {
                t.join(2_000L)
            }
        }

        fun joinThread() = thread?.join(2_000)
    }

    private class RecordingController(
        private val detectorReleased: () -> Boolean,
        private val startResults: MutableList<VoiceInputStartResult>,
        private val sharedOrder: MutableList<String>,
    ) : VoiceInputController {
        private val eventChannel = Channel<VoiceInputEvent>(Channel.UNLIMITED)
        override val events: Flow<VoiceInputEvent> = eventChannel.receiveAsFlow()

        val startCalls = mutableListOf<Long>()
        val releasedWhenStarted = mutableListOf<Boolean>()
        var stopCalls = 0

        override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
            val result = startResults.removeAt(0) as VoiceInputStartResult.Started
            sharedOrder += "stt-start-${result.captureSessionId}"
            startCalls += result.captureSessionId
            releasedWhenStarted += detectorReleased()
            return result
        }

        override fun stopListening() {
            stopCalls++
            sharedOrder += "stt-stop"
        }

        suspend fun emit(event: VoiceInputEvent) {
            eventChannel.send(event)
        }
    }

    private fun cuePlayer(): StartListeningCuePlayer =
        mockk<StartListeningCuePlayer>().apply {
            every { playCue(StartListeningCueContext.WAKE_WORD) } returns
                StartListeningCueResult(started = true, context = StartListeningCueContext.WAKE_WORD)
        }

    private fun journal(
        events: MutableList<Pair<String, Long>>,
    ) = WakeSessionJournal(
        generationId = 7L,
        sessionId = 9L,
        emit = { type, g, s, _ -> events += type to s },
    )

    // ── Ordering: detector release before attempt-1 STT start ─────────────────

    @Test
    fun `attempt-1 STT start is ordered after detector microphone release`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(VoiceInputStartResult.Started(1L)),
            sharedOrder = order,
        )
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var rearmCount = 0
        var routedCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { routedCount++; true },
                onSessionTerminal = { rearmCount++ },
            )
        }
        runCurrent()

        // The detector is stopped before any live STT startup.
        assertEquals("detector-stop", order.first())
        assertTrue(
            order.indexOf("detector-stop") < order.indexOf("stt-start-1"),
            "detector stop must precede attempt-1 STT start",
        )

        // Attempt 1 reaches readiness and captures the command.
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 1L))
        runCurrent()
        job.join()

        assertEquals(listOf(1L), controller.startCalls, "exactly one capture session on the normal path")
        assertEquals(listOf(true), controller.releasedWhenStarted, "mic released before STT start")
        assertEquals(1, routedCount)
        assertEquals(1, rearmCount, "detector re-arms exactly once after the session")

        // Journal correlation and ordering (all events in the original generation/session).
        val recorded = journalEvents.map { it.first }
        assertTrue(journalEvents.all { it.second == 9L }, "all events stay in the wake session")
        assertTrue(
            recorded.indexOf(AcousticEventType.VOICE_SESSION_STARTED) <
                recorded.indexOf(AcousticEventType.STT_START_REQUESTED),
        )
        assertTrue(
            recorded.indexOf(AcousticEventType.STT_START_REQUESTED) <
                recorded.indexOf(AcousticEventType.STT_READY),
        )
        assertTrue(
            recorded.indexOf(AcousticEventType.STT_READY) <
                recorded.indexOf(AcousticEventType.CUE_REQUESTED),
            "cue must be requested only after STT_READY",
        )
        assertTrue(
            recorded.indexOf(AcousticEventType.STT_FINAL) <
                recorded.indexOf(AcousticEventType.COMMAND_ROUTING_RESULT),
        )
        assertTrue(
            recorded.indexOf(AcousticEventType.COMMAND_ROUTING_RESULT) <
                recorded.indexOf(AcousticEventType.SESSION_COMPLETED),
        )
        assertFalse(recorded.contains(AcousticEventType.STT_ERROR))
    }

    @Test
    fun `handoff stop joins the detecting thread and completes without deadlock`() = runTest {
        val detector = ThreadedDetector()
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(VoiceInputStartResult.Started(1L)),
            sharedOrder = mutableListOf(),
        )
        var rearmCount = 0
        var handoffJob: Job? = null
        val callbackRan = CountDownLatch(1)

        // The detection callback runs on the detector thread (as in production) and
        // launches the handoff asynchronously; the handoff's stop() joins that thread.
        detector.start(generationId = 7L, onDetected = {
            handoffJob = launch {
                runWakeCommandHandoff(
                    wakeWordDetector = detector,
                    voiceInputController = controller,
                    cuePlayer = cuePlayer(),
                    generationId = 7L,
                    sessionId = 9L,
                    routeTranscript = { true },
                    onSessionTerminal = { rearmCount++ },
                )
            }
            callbackRan.countDown()
        }, verifyWindow = null)
        assertTrue(
            callbackRan.await(2, TimeUnit.SECONDS),
            "detection callback must have launched the handoff",
        )

        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 1L))
        runCurrent()
        handoffJob?.join()
        detector.joinThread()

        assertEquals(listOf(true), controller.releasedWhenStarted)
        assertEquals(listOf(1L), controller.startCalls)
        assertEquals(1, rearmCount)
    }

    // ── Normal path: attempt 1 succeeds, no retry ────────────────────────────

    @Test
    fun `successful first attempt does not start attempt 2`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(VoiceInputStartResult.Started(1L)),
            sharedOrder = order,
        )
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var rearmCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { true },
                onSessionTerminal = { rearmCount++ },
            )
        }
        runCurrent()
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 1L))
        runCurrent()
        job.join()

        assertEquals(listOf(1L), controller.startCalls)
        val attempts = journalEvents.filter { it.first == AcousticEventType.STT_START_REQUESTED }
        assertEquals(1, attempts.size, "no attempt 2 on the normal path")
        assertEquals(1, rearmCount)
    }

    @Test
    fun `foreign-mode events are ignored by the wake session`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(VoiceInputStartResult.Started(1L)),
            sharedOrder = order,
        )
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var routedCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { routedCount++; true },
                onSessionTerminal = {},
            )
        }
        runCurrent()
        // A non-alert session event with the same capture session id must not satisfy
        // the wake session's readiness wait.
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 1L))
        runCurrent()
        job.join()

        assertEquals(listOf(1L), controller.startCalls)
        assertEquals(1, routedCount)
    }

    // ── Retry remains available for genuine failures ─────────────────────────

    @Test
    fun `genuine post-readiness recognition failure still uses the bounded retry`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(
                VoiceInputStartResult.Started(1L),
                VoiceInputStartResult.Started(2L),
            ),
            sharedOrder = order,
        )
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var routedCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { routedCount++; true },
                onSessionTerminal = {},
            )
        }
        runCurrent()
        // Attempt 1 reaches readiness then fails for a genuine recognition reason.
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "audio input problem", captureSessionId = 1L))
        runCurrent()
        // Attempt 2 starts only after attempt 1 is terminal, then captures the command.
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 2L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 2L))
        runCurrent()
        job.join()

        assertEquals(listOf(1L, 2L), controller.startCalls, "retry runs after a genuine post-readiness failure")
        assertEquals(1, routedCount)
        val recorded = journalEvents.map { it.first }
        assertTrue(recorded.contains(AcousticEventType.STT_ERROR))
    }

    @Test
    fun `obsolete attempt-1 callback cannot terminate attempt 2`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(
                VoiceInputStartResult.Started(1L),
                VoiceInputStartResult.Started(2L),
            ),
            sharedOrder = order,
        )
        var routedCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                routeTranscript = { routedCount++; true },
                onSessionTerminal = {},
            )
        }
        runCurrent()
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "first attempt failed", captureSessionId = 1L))
        runCurrent()
        controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 2L))
        // A stale error from attempt 1's session arrives while attempt 2 is live.
        controller.emit(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "stale", captureSessionId = 1L))
        controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = 2L))
        runCurrent()
        job.join()

        assertEquals(listOf(1L, 2L), controller.startCalls)
        assertEquals(1, routedCount, "stale attempt-1 error must not terminate attempt 2")
    }

    // ── Cancellation, re-arm and no leftover recognizer ──────────────────────

    @Test
    fun `cancellation during handoff stops the recognizer and re-arms exactly once`() = runTest {
        val order = mutableListOf<String>()
        val detector = OrderingDetector(order)
        val controller = RecordingController(
            detectorReleased = { detector.released },
            startResults = mutableListOf(VoiceInputStartResult.Started(1L)),
            sharedOrder = order,
        )
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var rearmCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { true },
                onSessionTerminal = { rearmCount++ },
            )
        }
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(1, controller.stopCalls, "no recognizer may survive a cancelled handoff")
        assertEquals(1, rearmCount, "re-arm happens once even on cancellation")
        assertTrue(
            journalEvents.map { it.first }.contains(AcousticEventType.SESSION_CANCELLED),
        )
        assertFalse(job.isActive)
    }

    @Test
    fun `each confirmed activation produces exactly one voice session`() = runTest {
        // Two sequential confirmed activations each run one capture session and one re-arm.
        for (activation in 1..2) {
            val order = mutableListOf<String>()
            val detector = OrderingDetector(order)
            val controller = RecordingController(
                detectorReleased = { detector.released },
                startResults = mutableListOf(VoiceInputStartResult.Started(activation.toLong())),
                sharedOrder = order,
            )
            var rearmCount = 0

            val job = launch {
                runWakeCommandHandoff(
                    wakeWordDetector = detector,
                    voiceInputController = controller,
                    cuePlayer = cuePlayer(),
                    generationId = 7L,
                    sessionId = activation.toLong(),
                    routeTranscript = { true },
                    onSessionTerminal = { rearmCount++ },
                )
            }
            runCurrent()
            controller.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = activation.toLong()))
            controller.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "what time is it", captureSessionId = activation.toLong()))
            runCurrent()
            job.join()

            assertEquals(listOf(activation.toLong()), controller.startCalls)
            assertEquals(1, rearmCount)
        }
    }
}
