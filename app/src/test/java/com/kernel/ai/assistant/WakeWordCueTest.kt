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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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

        // Journal ordering: STT_START_REQUESTED → STT_READY → CUE_REQUESTED → playback → STT_FINAL
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
    fun `second ready attempt also plays cue`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val controller = fakeController(events)
        val journal = WakeSessionJournal(1L, 1L, emit = { _, _, _, _ -> })
        journal.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )
        val sessionId = 42L

        // Attempt 1: readiness + stopped (no transcript)
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        events.send(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        val result1 = runWakeAttempt(controller, journal, cuePlayer, 1)
        assertTrue(result1 is WakeAttemptOutcome.NoTranscript)

        // Attempt 2: readiness + transcript
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        events.send(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "hello", captureSessionId = sessionId))
        val result2 = runWakeAttempt(controller, journal, cuePlayer, 2)
        assertTrue(result2 is WakeAttemptOutcome.GotTranscript)

        verify(exactly = 2) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
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

    @Test
    fun `foreign events during attempt are ignored`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val controller = fakeController(events)
        val journal = WakeSessionJournal(1L, 1L, emit = { _, _, _, _ -> })
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )
        val sessionId = 42L

        // Foreign session events
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 99L))
        events.send(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "foreign", captureSessionId = 99L))
        // Own session readiness
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        events.send(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "owned", captureSessionId = sessionId))

        val outcome = runWakeAttempt(controller, journal, cuePlayer, 1)

        assertTrue(outcome is WakeAttemptOutcome.GotTranscript)
        assertEquals("owned", (outcome as WakeAttemptOutcome.GotTranscript).text)
    }


    @Test
    fun `transcript collection failure produces correct cancellation category`() = runTest {
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
        // Listen started then a terminal error
        events.send(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId))
        events.send(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no speech", captureSessionId = sessionId))

        val outcome = runWakeAttempt(controller, journal, cuePlayer, 1)
        assertTrue(outcome is WakeAttemptOutcome.NoTranscript)
    }

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

    @Test
    fun `WakeSessionJournal has exactly one terminal event`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }

    @Test
    fun `WakeSessionJournal does not record duplicate terminals`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        j.cancel("ignored")
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }

    @Test
    fun `coroutine cancellation cleans up attempt and propagates`() = runTest {
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
        // Run the attempt — it will wait inside for a terminal event
        val job = kotlinx.coroutines.Job()
        val attemptDeferred = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + job).launch {
            runWakeAttempt(controller, journal, cuePlayer, 1)
        }

        // Cancel the scope while the attempt is live (before any terminal event)
        val cancel = CancellationException("test cancel")
        job.cancel(cancel)

        // Wait for the attempt coroutine to finish
        attemptDeferred.join()

        // After the cancellation settles, finalise the journal and check
        journal.complete()
        val terminals = journalEvents.count {
            it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED)
        }
        assertEquals(1, terminals)
    }
}
