package com.kernel.ai.assistant

import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
class WakeWordCueTest {

    private fun fakeController(
        events: Channel<VoiceInputEvent>,
        startResult: VoiceInputStartResult = VoiceInputStartResult.Started(42L),
    ): VoiceInputController {
        val c = mockk<VoiceInputController>()
        every { c.events } returns events.receiveAsFlow()
        coEvery { c.startListening(VoiceCaptureMode.AlertCommand) } returns startResult
        return c
    }

    // --- runWakeAttempt tests (unit-level) ---

    @Test
    fun `first ready attempt plays cue and returns transcript`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val controller = fakeController(events)
        val journalEvents = mutableListOf<String>()
        val journal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> journalEvents.add(type) })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        val sessionId = 42L
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        events.send(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "hello", captureSessionId = sessionId))

        val outcome = runWakeAttempt(controller, journal, cuePlayer, 1)

        assertTrue(outcome is WakeAttemptOutcome.GotTranscript)
        assertEquals("hello", (outcome as WakeAttemptOutcome.GotTranscript).text)
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }

        // Journal ordering
        val startIdx = journalEvents.indexOf(AcousticEventType.STT_START_REQUESTED)
        val readyIdx = journalEvents.indexOf(AcousticEventType.STT_READY)
        val cueReqIdx = journalEvents.indexOf(AcousticEventType.CUE_REQUESTED)
        val playbackIdx = journalEvents.indexOfFirst {
            it == AcousticEventType.CUE_PLAYBACK_STARTED || it == AcousticEventType.CUE_PLAYBACK_ERROR
        }
        val finalIdx = journalEvents.indexOf(AcousticEventType.STT_FINAL)

        assertTrue(startIdx >= 0, "STT_START_REQUESTED must be recorded")
        assertTrue(readyIdx >= 0, "STT_READY must be recorded")
        assertTrue(cueReqIdx >= 0, "CUE_REQUESTED must be recorded")
        assertTrue(playbackIdx >= 0, "playback result must be recorded")
        assertTrue(finalIdx >= 0, "STT_FINAL must be recorded")
        assertTrue(startIdx < readyIdx, "STT_START_REQUESTED before STT_READY")
        assertTrue(cueReqIdx < playbackIdx, "CUE_REQUESTED before playback result")
        assertTrue(playbackIdx < finalIdx, "playback result before STT_FINAL")
    }

    @Test
    fun `no cue before readiness`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val controller = fakeController(events)
        val cuePlayer = mockk<StartListeningCuePlayer>(relaxUnitFun = true)
        val sessionId = 42L
        events.send(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no start", captureSessionId = sessionId))

        val outcome = runWakeAttempt(controller, WakeSessionJournal(1L, 1L, emit = { _, _, _, _ -> }), cuePlayer, 1)

        assertTrue(outcome is WakeAttemptOutcome.NoTranscript)
        verify(exactly = 0) { cuePlayer.playCue(any()) }
    }

    // --- runWakeCaptureSession tests (retry-loop level) ---

    @Test
    fun `first attempt success returns transcript`() = runTest {
        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            WakeAttemptOutcome.GotTranscript("hello")
        }
        assertEquals("hello", result.transcript)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `second attempt success returns transcript`() = runTest {
        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            if (attempt == 1) WakeAttemptOutcome.NoTranscript("stt_stopped_without_result")
            else WakeAttemptOutcome.GotTranscript("hello")
        }
        assertEquals("hello", result.transcript)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun `two failed attempts return no transcript`() = runTest {
        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            WakeAttemptOutcome.NoTranscript("stt_stopped_without_result")
        }
        assertEquals(null, result.transcript)
        assertEquals("stt_stopped_without_result", result.cancellationCategory)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun `unavailable stops immediately with stt_unavailable`() = runTest {
        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            WakeAttemptOutcome.Unavailable
        }
        assertEquals(null, result.transcript)
        assertEquals("stt_unavailable", result.cancellationCategory)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `flow failure before readiness produces startup_collection_failed`() = runTest {
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns flow<VoiceInputEvent> {
            throw RuntimeException("connection lost")
        }
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val journalEvents = mutableListOf<String>()
        val journal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> journalEvents.add(type) })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>(relaxUnitFun = true)

        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            runWakeAttempt(controller, journal, cuePlayer, attempt)
        }

        assertEquals(null, result.transcript)
        assertEquals("startup_collection_failed", result.cancellationCategory)
        assertEquals(listOf(1), attempts)
        verify(exactly = 0) { cuePlayer.playCue(any()) }
    }

    @Test
    fun `flow failure after readiness produces transcript_collection_failed`() = runTest {
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns flow {
            emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
            throw RuntimeException("connection lost after readiness")
        }
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val journalEvents = mutableListOf<String>()
        val journal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> journalEvents.add(type) })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        val attempts = mutableListOf<Int>()
        val result = runWakeCaptureSession { attempt ->
            attempts.add(attempt)
            runWakeAttempt(controller, journal, cuePlayer, attempt)
        }

        assertTrue(journalEvents.contains(AcousticEventType.STT_READY), "STT_READY must be recorded")
        assertTrue(journalEvents.contains(AcousticEventType.CUE_REQUESTED), "CUE_REQUESTED must be recorded")
        val playbackResults = journalEvents.count {
            it in setOf(AcousticEventType.CUE_PLAYBACK_STARTED, AcousticEventType.CUE_PLAYBACK_ERROR)
        }
        assertEquals(1, playbackResults, "exactly one playback-result event")
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }

        assertEquals(null, result.transcript)
        assertEquals("transcript_collection_failed", result.cancellationCategory)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `fatal collector error propagates without classification`() = runTest {
        val sessionId = 42L
        val fatal = AssertionError("fatal collector failure")

        val controller = mockk<VoiceInputController>()
        every { controller.events } returns flow<VoiceInputEvent> {
            throw fatal
        }
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val journalEvents = mutableListOf<String>()
        val journal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> journalEvents.add(type) })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>(relaxUnitFun = true)
        // The Error must propagate from runWakeAttempt, not be caught and classified
        val thrown = try {
            runWakeAttempt(
                voiceInputController = controller,
                journal = journal,
                cuePlayer = cuePlayer,
                attempt = 1,
            )
            null // no exception — would fail via assertNotNull below
        } catch (e: AssertionError) {
            e
        }

        assertNotNull(thrown, "AssertionError must propagate from runWakeAttempt")
        assertEquals("fatal collector failure", thrown!!.message)
        verify(exactly = 0) { cuePlayer.playCue(any()) }
    }

    @Test
    fun `cancellation propagates through runWakeCaptureSession without conversion`() = runTest {
        val attempts = mutableListOf<Int>()
        var caught: CancellationException? = null
        try {
            runWakeCaptureSession { attempt ->
                attempts.add(attempt)
                throw CancellationException("test cancel")
            }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught, "CancellationException must propagate")
        assertEquals("test cancel", caught!!.message)
        assertEquals(listOf(1), attempts, "only one attempt started, no retry")
        assertFalse(caught is WakeAttemptCollectionException)
    }
    @Test
    fun `integrated two-attempt retry with real runWakeAttempt`() = runTest {
        val sessionId = 42L

        // Use flow builder that produces events synchronously
        val attempt1Events = mutableListOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId),
        )
        val attempt2Events = mutableListOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId),
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "hello from attempt 2", captureSessionId = sessionId),
        )

        val attemptQueues = listOf(attempt1Events, attempt2Events)
        var attIdx = 0
        val controller = mockk<VoiceInputController>()
        every { controller.events } answers {
            val queue = attemptQueues[attIdx]
            flow {
                for (e in queue) emit(e)
            }
        }
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } answers {
            VoiceInputStartResult.Started(sessionId)
        }

        val journalEvents = mutableListOf<String>()
        val journal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> journalEvents.add(type) })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        val result = runWakeCaptureSession { attempt ->
            attIdx = attempt - 1
            runWakeAttempt(
                voiceInputController = controller,
                journal = journal,
                cuePlayer = cuePlayer,
                attempt = attempt,
            )
        }

        assertEquals("hello from attempt 2", result.transcript)
        verify(exactly = 2) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
    }

    @Test
    fun `cancelled session records exactly one terminal event`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        finalizeWakeSession(j, completed = false, "session_cancelled")
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
        assertTrue(events.contains(AcousticEventType.SESSION_CANCELLED))
    }

    // --- Cue-level tests ---

    @Test
    fun `successful cue metadata includes all required fields`() {
        val result = StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
            policyVersion = "2026-07-cue-v1", selectedStream = 4,
            currentVolume = 10, maxVolume = 25, routeClassification = "built_in_speaker",
        )
        val meta = cueMetadata(result)
        assertEquals("wake_word", meta["context"])
        assertEquals("2026-07-cue-v1", meta["policy_version"])
        assertEquals("4", meta["stream"])
        assertEquals("10", meta["current_volume"])
        assertEquals("25", meta["max_volume"])
        assertEquals("built_in_speaker", meta["route"])
    }

    @Test
    fun `failed cue metadata includes category`() {
        val result = StartListeningCueResult(
            started = false, context = StartListeningCueContext.WAKE_WORD,
            policyVersion = "2026-07-cue-v1", failureCategory = "playback_start_failed",
        )
        val meta = cueMetadata(result, isError = true)
        assertEquals("playback_start_failed", meta["category"])
    }

    @Test
    fun `playWakeCue invokes player with WAKE_WORD context`() {
        val j = WakeSessionJournal(1L, 1L, emit = { _, _, _, _ -> })
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )
        playWakeCue(j, cuePlayer)
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
    }

    @Test
    fun `successful cue records CUE_REQUESTED then CUE_PLAYBACK_STARTED`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )
        playWakeCue(j, cuePlayer)
        assertTrue(events.contains(AcousticEventType.CUE_REQUESTED))
        assertTrue(events.contains(AcousticEventType.CUE_PLAYBACK_STARTED))
    }
}
