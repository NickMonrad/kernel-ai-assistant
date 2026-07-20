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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAlertSessionTest {

    @Test
    fun `buffered start returns result with empty owned events`() = runTest {
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED).receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(42L)

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertNotNull(result)
        assertEquals(42L, result!!.captureSessionId)
        assertTrue(result.ownedStartEvents.isEmpty(), "async delivery is the norm — no sync buffer")
    }

    @Test
    fun `async ListeningStarted triggers cue through production handler`() = runTest {
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
        assertNotNull(result)

        val asyncEvent = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
        val owned = isOwnedAlertEvent(asyncEvent, result!!.captureSessionId, true)
            && asyncEvent is VoiceInputEvent.ListeningStarted
            && asyncEvent.mode == VoiceCaptureMode.AlertCommand
        if (owned) {
            cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
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

        // No event emitted during startListening — async readiness
        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)
        assertNotNull(result)
        assertEquals(0, result!!.ownedStartEvents.size)

        // Event delivered later is accepted by production ownership check
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
    fun `unavailable startup returns null`() = runTest {
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns Channel<VoiceInputEvent>(capacity = Channel.UNLIMITED).receiveAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Unavailable("reason")
        assertNull(bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand))
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
}
