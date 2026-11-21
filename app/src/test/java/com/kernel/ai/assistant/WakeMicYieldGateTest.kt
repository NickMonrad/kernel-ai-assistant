package com.kernel.ai.assistant

import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WakeMicYieldGateTest {

    private var released = 0
    private var rearmed = 0
    private val gate = WakeMicYieldGate(
        releaseMic = { released++ },
        rearmMic = { rearmed++ },
    )

    private val mode = VoiceCaptureMode.AlertCommand

    @Test
    fun `pre-start ListeningRequested releases the mic (#2027)`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = true)

        assertEquals(1, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `ListeningStarted after ListeningRequested does not release again, and one stop re-arms once`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 1L), rearmAllowed = true)
        assertEquals(1, released)
        assertEquals(0, rearmed)

        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L), rearmAllowed = true)
        assertEquals(1, released)
        assertEquals(1, rearmed)
    }

    @Test
    fun `each voice session releases and re-arms exactly once`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 2L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 2L), rearmAllowed = true)

        assertEquals(2, released)
        assertEquals(2, rearmed)
    }

    @Test
    fun `refresh re-announce inside a session does not release twice`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = true)

        assertEquals(1, released)
    }

    @Test
    fun `ListeningStarted alone still releases, as the Sherpa path has always done`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L), rearmAllowed = true)

        assertEquals(1, released)
        assertEquals(1, rearmed)
    }

    @Test
    fun `stop during a wake handoff does not re-arm, and the next session still yields`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L), rearmAllowed = false)
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L), rearmAllowed = false)
        assertEquals(1, released)
        assertEquals(0, rearmed)

        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L), rearmAllowed = true)
        assertEquals(2, released)
    }

    @Test
    fun `non-lifecycle events neither release nor re-arm`() {
        gate.onVoiceEvent(VoiceInputEvent.SpeechDetected(mode, 1L), rearmAllowed = true)
        gate.onVoiceEvent(VoiceInputEvent.Transcript(mode, "hi", 1L), rearmAllowed = true)

        assertEquals(0, released)
        assertEquals(0, rearmed)
    }
}
