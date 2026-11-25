package com.kernel.ai.assistant

import android.media.MediaRecorder

/** One active microphone client from `AudioRecordingCallback`, reduced to what arbitration needs (#2108). */
internal data class MicRecordingClient(val sessionId: Int, val source: Int, val silenced: Boolean)

/**
 * Steps wake capture aside while another app records, and re-arms when that ends (#2108).
 *
 * Owner rule (#2109): always-on wake and hotword listeners are not recordings. Other apps' clients
 * that capture with [MediaRecorder.AudioSource.VOICE_RECOGNITION] are ignored. Recorders, camera
 * video and calls use other sources (MIC, CAMCORDER, VOICE_COMMUNICATION, ...) and pause Jandal.
 * Our own capture, identified by [ownSessionId], never pauses us unless the audio server silenced it.
 *
 * [rearmAllowed] is false while a voice session or wake handoff owns the mic; the transition out of
 * contention then does not re-arm, and that owner re-arms on its own terminal event. [rearmMic] must
 * still refuse to take the mic while [paused] is set, because the voice path calls it independently.
 */
internal class WakeMicContentionGate(
    private val releaseMic: () -> Unit,
    private val rearmMic: () -> Unit,
    private val rearmAllowed: () -> Boolean,
    private val ownSessionId: () -> Int?,
) {
    /** True while another app holds the microphone, so the wake detector must not capture. */
    @Volatile var paused: Boolean = false
        private set

    /** Called with the full active-client list on every recording-configuration change. */
    fun onRecordingClients(clients: List<MicRecordingClient>) {
        val own = ownSessionId()
        val contended = clients.any { client ->
            if (client.sessionId == own) {
                client.silenced
            } else {
                client.source !in ALWAYS_ON_SOURCES
            }
        }
        when {
            contended && !paused -> {
                paused = true
                releaseMic()
            }
            !contended && paused -> {
                paused = false
                if (rearmAllowed()) rearmMic()
            }
        }
    }

    companion object {
        /**
         * Sources used by always-on listeners (wake words, hotwords, assistant dictation). A client on one
         * of these never pauses Jandal. Only the public VOICE_RECOGNITION constant is listed: the hidden
         * HOTWORD source cannot be referenced from the SDK.
         */
        val ALWAYS_ON_SOURCES: Set<Int> = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
    }
}
