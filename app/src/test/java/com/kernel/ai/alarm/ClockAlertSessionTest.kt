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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAlertSessionTest {

    // --- shouldPlayClockAlertListeningCue tests (shared production helper) ---

    @Test
    fun `owned AlertCommand readiness returns true`() {
        assertTrue(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `owned Command readiness returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `owned SlotReply readiness returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.SlotReply, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `foreign session readiness returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 99L), 42L, true,
        ))
    }

    @Test
    fun `readiness while not listening returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L), 42L, false,
        ))
    }

    @Test
    fun `transcript event returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "stop", captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `error event returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "fail", captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `stopped event returns false`() {
        assertFalse(shouldPlayClockAlertListeningCue(
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = 42L), 42L, true,
        ))
    }

    // --- Synchronous readiness captured by bufferedCaptureSession ---

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
    fun `synchronous readiness passes shared helper exactly once`() = runTest(UnconfinedTestDispatcher()) {
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

        var cueCount = 0
        for (event in started.ownedStartEvents) {
            if (shouldPlayClockAlertListeningCue(event, started.captureSessionId, true)) {
                cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
                cueCount++
            }
        }
        assertEquals(1, cueCount)
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) }
    }

    @Test
    fun `asynchronous readiness passes shared helper exactly once`() = runTest {
        val events = Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED)
        val sessionId = 42L
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns events.receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(sessionId)

        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.CLOCK_ALERT,
        )

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started
        assertEquals(0, started.ownedStartEvents.size)

        var cueCount = 0
        val asyncEvent = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = started.captureSessionId)
        if (shouldPlayClockAlertListeningCue(asyncEvent, started.captureSessionId, true)) {
            cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
            cueCount++
        }
        assertEquals(1, cueCount)
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) }
    }

    // --- Foreign events ---

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

    // --- Unavailable startup ---

    @Test
    fun `unavailable startup returns Unavailable result with original message`() = runTest {
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED).receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Unavailable("mic_busy")

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertTrue(result is CaptureStartResult.Unavailable)
        assertEquals("mic_busy", (result as CaptureStartResult.Unavailable).message)
    }

    // --- alertVoiceUnavailableMessage tests ---

    @Test
    fun `non-blank controller message is used as-is`() {
        assertEquals("mic is busy", alertVoiceUnavailableMessage("mic is busy"))
    }

    @Test
    fun `blank controller message falls back to generic`() {
        assertEquals("Voice commands are unavailable right now.", alertVoiceUnavailableMessage("  "))
    }

    @Test
    fun `null controller message falls back to generic`() {
        assertEquals("Voice commands are unavailable right now.", alertVoiceUnavailableMessage(null))
    }
}
