package com.kernel.ai.alarm

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAlertSessionTest {

    @Test
    fun `synchronous readiness is captured by buffered startup`() = runTest(UnconfinedTestDispatcher()) {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } answers {
            events.trySend(VoiceInputEvent.ListeningStarted(
                mode = VoiceCaptureMode.AlertCommand, captureSessionId = sessionId,
            ))
            VoiceInputStartResult.Started(sessionId)
        }

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started
        assertEquals(sessionId, started.captureSessionId)
        assertEquals(1, started.ownedStartEvents.size)
        assertTrue(started.ownedStartEvents[0] is VoiceInputEvent.ListeningStarted)
    }

    @Test
    fun `synchronous readiness triggers clock-alert cue through production handler`() = runTest(UnconfinedTestDispatcher()) {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } answers {
            events.trySend(VoiceInputEvent.ListeningStarted(
                mode = VoiceCaptureMode.AlertCommand, captureSessionId = sessionId,
            ))
            VoiceInputStartResult.Started(sessionId)
        }

        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.CLOCK_ALERT,
        )

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started

        for (event in started.ownedStartEvents) {
            if (isOwnedAlertEvent(event, started.captureSessionId, true) && event is VoiceInputEvent.ListeningStarted && event.mode == VoiceCaptureMode.AlertCommand) {
                cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
            }
        }
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) }
    }

    @Test
    fun `asynchronous readiness returns no buffered event and is owned later`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started
        assertEquals(0, started.ownedStartEvents.size)

        val later = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
        assertTrue(isOwnedAlertEvent(later, sessionId, true))
    }

    @Test
    fun `owned AlertCommand readiness is accepted by production path`() {
        val sessionId = 42L
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId), sessionId, true,
        ))
    }

    @Test
    fun `Command and SlotReply modes pass ownership filter`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 42L), 42L, true,
        ))
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.SlotReply, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `foreign session events are filtered`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 99L), 42L, true,
        ).not())
    }

    @Test
    fun `unavailable startup returns Unavailable result with original message`() = runTest {
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED).receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Unavailable("mic_busy")

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Unavailable)
        assertEquals("mic_busy", (result as CaptureStartResult.Unavailable).message)
    }

    @Test
    fun `foreign terminal events filtered by ownership`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "stop", captureSessionId = 99L), 42L, true,
        ).not())
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "fail", captureSessionId = 99L), 42L, true,
        ).not())
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = 99L), 42L, true,
        ).not())
    }

    @Test
    fun `foreign-session events in synchronous startup are excluded`() = runTest(UnconfinedTestDispatcher()) {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val ownSessionId = 42L
        val foreignSessionId = 99L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } answers {
            events.trySend(VoiceInputEvent.ListeningStarted(
                mode = VoiceCaptureMode.AlertCommand, captureSessionId = foreignSessionId,
            ))
            events.trySend(VoiceInputEvent.ListeningStarted(
                mode = VoiceCaptureMode.AlertCommand, captureSessionId = ownSessionId,
            ))
            VoiceInputStartResult.Started(ownSessionId)
        }

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started
        assertEquals(1, started.ownedStartEvents.size)
        assertEquals(ownSessionId, started.ownedStartEvents[0].captureSessionId)
    }

    @Test
    fun `mode filtering through production handler`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(any()) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.CLOCK_ALERT,
        )

        fun fireAndCheck(mode: VoiceCaptureMode, sessionId: Long, expectedCue: Boolean) {
            val event = VoiceInputEvent.ListeningStarted(mode, captureSessionId = sessionId)
            if (isOwnedAlertEvent(event, sessionId, true) && event is VoiceInputEvent.ListeningStarted && event.mode == VoiceCaptureMode.AlertCommand) {
                cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
            }
        }

        fireAndCheck(VoiceCaptureMode.AlertCommand, sessionId, expectedCue = true)
        fireAndCheck(VoiceCaptureMode.Command, 43L, expectedCue = false)
        fireAndCheck(VoiceCaptureMode.SlotReply, 44L, expectedCue = false)

        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) }
    }
}
