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
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAlertSessionTest {

    @Test
    fun `CaptureStartResult stores session and events`() {
        val event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L)
        val result = CaptureStartResult(captureSessionId = 42L, ownedStartEvents = listOf(event))
        assertEquals(42L, result.captureSessionId)
        assertEquals(1, result.ownedStartEvents.size)
        assertTrue(result.ownedStartEvents[0] is VoiceInputEvent.ListeningStarted)
    }

    @Test
    fun `no buffered events when channel is empty`() = runTest {
        val channel = Channel<VoiceInputEvent>(capacity = Channel.BUFFERED)
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns channel.consumeAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Started(42L)

        val result = bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand)

        assertNotNull(result)
        assertEquals(0, result!!.ownedStartEvents.size)
    }

    @Test
    fun `buffered event triggers clock-alert cue through handler`() {
        val sessionId = 42L
        val event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = sessionId)
        val cuePlayer = mockk<StartListeningCuePlayer>(relaxUnitFun = true)
        every { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.CLOCK_ALERT,
        )

        assertTrue(isOwnedAlertEvent(event, sessionId, true))
        if (event.mode == VoiceCaptureMode.AlertCommand) {
            cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
        }
        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT) }
    }

    @Test
    fun `owned AlertCommand readiness accepted`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `other modes pass ownership filter`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 42L), 42L, true,
        ))
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.SlotReply, captureSessionId = 42L), 42L, true,
        ))
    }

    @Test
    fun `foreign session filtered`() {
        assertTrue(isOwnedAlertEvent(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 99L), 42L, true,
        ).not())
    }

    @Test
    fun `unavailable startup returns null`() = runTest {
        val controller = mockk<VoiceInputController>()
        every { controller.events } returns Channel<VoiceInputEvent>().consumeAsFlow()
        coEvery { controller.startListening(VoiceCaptureMode.AlertCommand) } returns VoiceInputStartResult.Unavailable("reason")
        assertNull(bufferedCaptureSession(controller, VoiceCaptureMode.AlertCommand))
    }

    @Test
    fun `foreign terminal events filtered`() {
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
