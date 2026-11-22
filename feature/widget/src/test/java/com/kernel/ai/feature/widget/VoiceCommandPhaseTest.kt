package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.NO_SPEECH_WINDOW_EXHAUSTED
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `processing label stays visible as caption once partial transcripts arrive`() {
        assertEquals(
            "turn on the lights" to "Processing…",
            voiceOverlayLabels(VoiceCommandPhase.Processing, "turn on the lights", failed = false),
        )
    }

    @Test
    fun `listening shows the label alone until the first partial`() {
        assertEquals("Listening…" to null, voiceOverlayLabels(VoiceCommandPhase.Listening, "", failed = false))
        assertEquals("hi" to "Listening…", voiceOverlayLabels(VoiceCommandPhase.Listening, "hi", failed = false))
    }

    @Test
    fun `silent overlay capture is not restarted`() {
        val silent = VoiceInputEvent.Error(
            mode = VoiceCaptureMode.AlertCommand,
            message = "No speech heard. Try again.",
            category = NO_SPEECH_WINDOW_EXHAUSTED,
        )
        assertFalse(restartsAfterError(silent, mayRetry = true))
    }

    @Test
    fun `recognizer failure restarts once and not after the retry is spent`() {
        val failure = VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "Microphone stopped. Try again.")
        assertTrue(restartsAfterError(failure, mayRetry = true))
        assertFalse(restartsAfterError(failure, mayRetry = false))
    }

    @Test
    fun `failure shows the error text without a phase caption`() {
        assertEquals(
            "Voice commands are unavailable right now." to null,
            voiceOverlayLabels(VoiceCommandPhase.Starting, "Voice commands are unavailable right now.", failed = true),
        )
    }
}
