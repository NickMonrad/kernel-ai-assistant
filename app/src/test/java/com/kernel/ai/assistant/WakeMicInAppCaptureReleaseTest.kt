package com.kernel.ai.assistant

import android.media.MediaRecorder
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #2178: the in-app asr4all capture opens before the yield lands, so the audio server silences the wake capture
 * and [WakeMicContentionGate] holds a SILENCED back-off of 10 to 120 s. When that in-app capture stops, the wake
 * mic must re-arm at once instead of waiting out the hold. The two gates are wired the way [WakeWordService] wires them.
 */
class WakeMicInAppCaptureReleaseTest {

    private val wakeSession = 101
    private val inAppSession = 202
    private val mode = VoiceCaptureMode.AlertCommand
    private var now = 0L
    private var detectorArmed = true
    private var clients: List<MicRecordingClient> = emptyList()
    private var rearmedAt = -1L
    private lateinit var contention: WakeMicContentionGate
    private lateinit var yieldGate: WakeMicYieldGate

    @BeforeEach
    fun setUp() {
        contention = WakeMicContentionGate(
            releaseMic = { detectorArmed = false },
            rearmMic = { rearmDetector() },
            rearmAllowed = { !yieldGate.voiceSessionActive },
            ownSessionIds = { setOf(wakeSession, inAppSession) },
            clockMs = { now },
            scheduleRecheck = {},
            onResumed = { _, _, _ -> },
        )
        yieldGate = WakeMicYieldGate(
            releaseMic = { detectorArmed = false },
            rearmMic = { rearmDetector() },
            rearmAllowed = { true },
            onOwnSessionEnded = { contention.onOwnVoiceCaptureEnded() },
            onStaleStop = { _, _ -> },
        )
    }

    /** Mirrors WakeWordService.rearmDetectorLocked: re-sync the contention gate while it is paused, otherwise arm. */
    private fun rearmDetector() {
        if (contention.paused) {
            contention.onRecordingClients(clients)
            return
        }
        detectorArmed = true
        rearmedAt = now
    }

    @Test
    fun `an in-app capture that silenced the wake mic re-arms at its stop, not after the SILENCED back-off`() {
        // The in-app capture opens before the yield lands, so the audio server silences the wake capture.
        clients = listOf(
            MicRecordingClient(wakeSession, MediaRecorder.AudioSource.VOICE_RECOGNITION, silenced = true),
            MicRecordingClient(inAppSession, MediaRecorder.AudioSource.MIC, silenced = false),
        )
        contention.onRecordingClients(clients)
        assertTrue(contention.paused)
        assertFalse(detectorArmed)

        // The yield lands while the in-app capture runs. The wake detector is already released.
        now = 100L
        yieldGate.onVoiceEvent(VoiceInputEvent.ListeningStarted(mode, inAppSession.toLong()))
        clients = listOf(MicRecordingClient(inAppSession, MediaRecorder.AudioSource.MIC, silenced = false))
        contention.onRecordingClients(clients)
        assertTrue(contention.paused)

        // The in-app capture stops. The wake mic re-arms at once, not at the 10 s hold.
        now = 2_000L
        clients = emptyList()
        yieldGate.onVoiceEvent(VoiceInputEvent.ListeningStopped(mode, inAppSession.toLong()))

        assertFalse(contention.paused)
        assertTrue(detectorArmed)
        assertEquals(2_000L, rearmedAt)
    }
}
