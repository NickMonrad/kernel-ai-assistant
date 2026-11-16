package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class WakeOverlayChannelTest {

    private val listening = WakeOverlayState.Active(WakeOverlayPhase.Listening, "")

    @Test
    fun `listening then partials then processing keeps processing once partials arrive`() {
        var state: WakeOverlayState = listening
        state = state.onWakeEvent(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.AlertCommand, "turn on"))
        assertEquals(WakeOverlayState.Active(WakeOverlayPhase.Listening, "turn on"), state)

        state = state.onWakeEvent(VoiceInputEvent.SpeechEnded(VoiceCaptureMode.AlertCommand))
        assertEquals(WakeOverlayState.Active(WakeOverlayPhase.Processing, "turn on"), state)

        // A late partial must not drop the Processing phase.
        state = state.onWakeEvent(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.AlertCommand, "turn on the lights"))
        assertEquals(WakeOverlayState.Active(WakeOverlayPhase.Processing, "turn on the lights"), state)
    }

    @Test
    fun `listening started restores listening after an error retry`() {
        val processing = WakeOverlayState.Active(WakeOverlayPhase.Processing, "half")
        val afterError = processing.onWakeEvent(VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no speech"))
        assertEquals(WakeOverlayState.Active(WakeOverlayPhase.Listening, ""), afterError)

        val retried = afterError.onWakeEvent(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand))
        assertEquals(WakeOverlayState.Active(WakeOverlayPhase.Listening, ""), retried)
    }

    @Test
    fun `events are ignored when no wake capture is active`() {
        assertEquals(
            WakeOverlayState.Idle,
            WakeOverlayState.Idle.onWakeEvent(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.AlertCommand, "x")),
        )
        val ended = WakeOverlayState.Ended(routed = false)
        assertEquals(ended, ended.onWakeEvent(VoiceInputEvent.SpeechEnded(VoiceCaptureMode.AlertCommand)))
    }

    @Test
    fun `route hands the overlay to the activity and end without a transcript closes it`() {
        val channel = WakeOverlayChannel
        channel.begin()
        channel.onEvent(VoiceInputEvent.SpeechEnded(VoiceCaptureMode.AlertCommand))
        channel.markRouted()
        assertEquals(WakeOverlayState.Ended(routed = true), channel.state.value)
        // The terminal call after a routed transcript must not overwrite the routed outcome.
        channel.end()
        assertEquals(WakeOverlayState.Ended(routed = true), channel.state.value)

        channel.begin()
        channel.end()
        assertEquals(WakeOverlayState.Ended(routed = false), channel.state.value)
    }

    @Test
    fun `a retry does not keep the previous attempt's partial text`() {
        val withOldText = WakeOverlayState.Active(WakeOverlayPhase.Listening, "old words")
        assertEquals(
            WakeOverlayState.Active(WakeOverlayPhase.Listening, ""),
            withOldText.onWakeEvent(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand)),
        )
    }

    @Test
    fun `cancel is honoured only for the live capture that asked for it`() {
        val channel = WakeOverlayChannel
        val first = channel.begin()
        assertTrue(channel.isLive(first))
        assertFalse(channel.isCancelRequested(first))
        channel.requestCancel(first)
        assertTrue(channel.isCancelRequested(first))
        channel.end()

        val second = channel.begin()
        assertFalse(channel.isLive(first), "a finished capture's token is no longer live")
        channel.requestCancel(first)
        assertFalse(
            channel.isCancelRequested(second),
            "a late cancel from the previous overlay must not cancel the new capture",
        )
        assertTrue(channel.isLive(second))

        channel.end()
        channel.requestCancel(second)
        assertFalse(channel.isLive(second), "no cancel is accepted once the capture ended")
    }
}
