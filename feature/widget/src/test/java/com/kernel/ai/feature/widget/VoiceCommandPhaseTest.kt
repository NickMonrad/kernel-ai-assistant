package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VoiceCommandPhaseTest {

    @Test
    fun `starting becomes listening when the recognizer reports listening`() {
        val next = phaseAfter(VoiceCommandPhase.Starting, VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand))
        assertEquals(VoiceCommandPhase.Listening, next)
    }

    @Test
    fun `listening becomes processing when speech ends`() {
        val next = phaseAfter(
            VoiceCommandPhase.Listening,
            VoiceInputEvent.SpeechEnded(VoiceCaptureMode.AlertCommand, captureSessionId = 1L),
        )
        assertEquals(VoiceCommandPhase.Processing, next)
    }

    @Test
    fun `transcript keeps the current phase because the activity finishes`() {
        assertEquals(
            VoiceCommandPhase.Listening,
            phaseAfter(VoiceCommandPhase.Listening, VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "x")),
        )
    }

    @Test
    fun `error resets to starting before a retry so the overlay does not show stale listening`() {
        assertEquals(
            VoiceCommandPhase.Starting,
            phaseAfter(VoiceCommandPhase.Listening, VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no speech")),
        )
        assertEquals(
            VoiceCommandPhase.Starting,
            phaseAfter(VoiceCommandPhase.Starting, VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "no speech")),
        )
    }
}
