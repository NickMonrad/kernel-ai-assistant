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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordCueTest {

    private val journal = WakeSessionJournal(
        generationId = 1L, sessionId = 1L, emit = { _, _, _, _ -> },
    )

    private suspend fun runWakeAttemptForTest(
        voiceInputController: VoiceInputController,
        journal: WakeSessionJournal,
        cuePlayer: StartListeningCuePlayer,
        afterStartup: suspend () -> VoiceInputEvent,
    ): VoiceInputEvent? {
        val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)
        if (result !is VoiceInputStartResult.Started) return null
        val startupEvent = afterStartup()
        if (startupEvent !is VoiceInputEvent.ListeningStarted) return startupEvent
        journal.record(AcousticEventType.STT_READY)
        playWakeCue(journal, cuePlayer)
        return afterStartup()
    }

    @Test
    fun `first ready attempt plays one cue and returns transcript`() = runTest {
        val events = mutableListOf<String>()
        val testJournal = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        testJournal.start()
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        var call = 0
        val terminal = runWakeAttemptForTest(controller, testJournal, cuePlayer) {
            call++
            when (call) {
                1 -> VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
                2 -> VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "hello", captureSessionId = sessionId)
                else -> VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
            }
        }

        assertTrue(terminal is VoiceInputEvent.Transcript)
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
        assertTrue(events.contains(AcousticEventType.STT_READY))
        assertTrue(events.contains(AcousticEventType.CUE_REQUESTED))
        val playbackEvents = events.filter { it in setOf(AcousticEventType.CUE_PLAYBACK_STARTED, AcousticEventType.CUE_PLAYBACK_ERROR) }
        assertEquals(1, playbackEvents.size)
    }

    @Test
    fun `second ready attempt also plays one cue`() = runTest {
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        val attempt1Journal = WakeSessionJournal(1L, 1L, emit = { _, _, _, _ -> })

        var call1 = 0
        runWakeAttemptForTest(controller, attempt1Journal, cuePlayer) {
            call1++
            when (call1) {
                1 -> VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
                else -> VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
            }
        }

        var call2 = 0
        runWakeAttemptForTest(controller, attempt1Journal, cuePlayer) {
            call2++
            when (call2) {
                1 -> VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
                else -> VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "hello", captureSessionId = sessionId)
            }
        }

        verify(exactly = 2) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
    }

    @Test
    fun `no cue before readiness`() = runTest {
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)
        val cuePlayer = mockk<StartListeningCuePlayer>(relaxUnitFun = true)

        val terminal = runWakeAttemptForTest(controller, journal, cuePlayer) {
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no start", captureSessionId = sessionId)
        }
        assertTrue(terminal is VoiceInputEvent.Error)
        verify(exactly = 0) { cuePlayer.playCue(any()) }
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
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )
        playWakeCue(journal, cuePlayer)
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
    fun `failed cue records CUE_REQUESTED then CUE_PLAYBACK_ERROR`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = false, context = StartListeningCueContext.WAKE_WORD, failureCategory = "error",
        )
        playWakeCue(j, cuePlayer)
        assertTrue(events.contains(AcousticEventType.CUE_REQUESTED))
        assertTrue(events.contains(AcousticEventType.CUE_PLAYBACK_ERROR))
    }

    @Test
    fun `WakeSessionJournal records exactly one terminal event on success`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }

    @Test
    fun `WakeSessionJournal records exactly one terminal event on cancel`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.cancel("failure")
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }

    @Test
    fun `WakeSessionJournal does not record duplicate terminal events`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        j.cancel("ignored")
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }
}
