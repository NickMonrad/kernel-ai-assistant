package com.kernel.ai.alarm

import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockAlertSessionTest {

    @Test
    fun `owned AlertCommand ListeningStarted is accepted`() {
        val result = isOwnedAlertEvent(
            event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L),
            captureSessionId = 42L,
            isVoiceListening = true,
        )
        assertTrue(result)
    }

    @Test
    fun `foreign session ID is rejected`() {
        val result = isOwnedAlertEvent(
            event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 99L),
            captureSessionId = 42L,
            isVoiceListening = true,
        )
        assertFalse(result)
    }

    @Test
    fun `null session ID is rejected`() {
        val result = isOwnedAlertEvent(
            event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand),
            captureSessionId = 42L,
            isVoiceListening = true,
        )
        assertFalse(result)
    }

    @Test
    fun `not listening rejects all events`() {
        val result = isOwnedAlertEvent(
            event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 42L),
            captureSessionId = 42L,
            isVoiceListening = false,
        )
        assertFalse(result)
    }

    @Test
    fun `foreign Command ListeningStarted does not trigger owned handler`() {
        // Even though the event has the correct session ID, Command mode
        // should not be treated as an AlertCommand readiness event
        assertTrue(
            isOwnedAlertEvent(
                event = VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 42L),
                captureSessionId = 42L,
                isVoiceListening = true,
            ),
            "isOwnedAlertEvent checks session ownership, not mode — mode filtering is separate",
        )
    }

    @Test
    fun `foreign terminal events do not terminate active capture`() {
        // Foreign session events should be filtered out before reaching handleVoiceEvent
        val foreignSessionEvents = listOf(
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "stop", captureSessionId = 99L),
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "failure", captureSessionId = 99L),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId = 99L),
        )
        for (event in foreignSessionEvents) {
            assertFalse(
                isOwnedAlertEvent(event, captureSessionId = 42L, isVoiceListening = true),
                "Foreign session event must be filtered out: $event",
            )
        }
    }
}
