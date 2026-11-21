package com.kernel.ai.assistant

import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WakeMicYieldGateTest {

    private var released = 0
    private var rearmed = 0
    private var rearmAllowedNow = true
    private lateinit var gate: WakeMicYieldGate

    private val mode = VoiceCaptureMode.AlertCommand

    @BeforeEach
    fun setUp() {
        released = 0
        rearmed = 0
        rearmAllowedNow = true
        gate = WakeMicYieldGate(
            releaseMic = { released++ },
            rearmMic = { rearmed++ },
            rearmAllowed = { rearmAllowedNow },
        )
    }

    @Test
    fun `pre-start ListeningRequested releases the mic (#2027)`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))

        assertEquals(1, released)
        assertEquals(0, rearmed)
        assertTrue(gate.voiceSessionActive)
    }

    @Test
    fun `ListeningStarted after ListeningRequested does not release again, and one stop re-arms once`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 1L))
        assertEquals(1, released)
        assertEquals(0, rearmed)

        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))
        assertEquals(1, released)
        assertEquals(1, rearmed)
        assertFalse(gate.voiceSessionActive)
    }

    @Test
    fun `each voice session releases and re-arms exactly once`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 2L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 2L))

        assertEquals(2, released)
        assertEquals(2, rearmed)
    }

    @Test
    fun `refresh re-announce inside a session does not release twice`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))

        assertEquals(1, released)
    }

    @Test
    fun `ListeningStarted alone still releases, as the Sherpa path has always done`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))

        assertEquals(1, released)
        assertEquals(1, rearmed)
    }

    @Test
    fun `stop during a wake handoff does not re-arm, and the next session still yields`() {
        rearmAllowedNow = false
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))
        assertEquals(1, released)
        assertEquals(0, rearmed)

        rearmAllowedNow = true
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L))
        assertEquals(2, released)
    }

    @Test
    fun `a stop for a replaced session is ignored while the new session owns the yield (#2027)`() {
        // Double-tap during the not-ready window: session 2 replaces session 1 without a stop for 1.
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))

        assertEquals(1, released)
        assertEquals(0, rearmed)
        assertTrue(gate.voiceSessionActive)

        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 2L))
        assertEquals(1, rearmed)
        assertFalse(gate.voiceSessionActive)
    }

    @Test
    fun `a replaced native session closed after a Zipformer hand-off does not re-arm under the live capture`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 2L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L))

        assertEquals(0, rearmed)
        assertTrue(gate.voiceSessionActive)
    }

    @Test
    fun `a superseded session's cancelled stop does not re-arm under the live capture (#2029)`() {
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningRequested(mode, 2L))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 1L, cancelled = true))
        gate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, 2L))

        assertEquals(1, released)
        assertEquals(0, rearmed)
        assertTrue(gate.voiceSessionActive)

        gate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, 2L))
        assertEquals(1, rearmed)
        assertFalse(gate.voiceSessionActive)
    }

    @Test
    fun `non-lifecycle events neither release nor re-arm`() {
        gate.onVoiceEvent(VoiceInputEvent.SpeechDetected(mode, 1L))
        gate.onVoiceEvent(VoiceInputEvent.Transcript(mode, "hi", 1L))

        assertEquals(0, released)
        assertEquals(0, rearmed)
    }
}
