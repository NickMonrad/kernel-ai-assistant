package com.kernel.ai.assistant

import android.media.MediaRecorder

/** One active microphone client from `AudioRecordingCallback`, reduced to what arbitration needs (#2108). */
internal data class MicRecordingClient(val sessionId: Int, val source: Int, val silenced: Boolean)

/**
 * Steps wake capture aside while another app records, and re-arms when that ends (#2108).
 *
 * Owner rule (#2109): always-on wake and hotword listeners are not recordings. Other apps' clients
 * that capture with [MediaRecorder.AudioSource.VOICE_RECOGNITION] or the hidden hotword source
 * ([HOTWORD_SOURCE]) are ignored. Recorders, camera video and calls use other sources (MIC, CAMCORDER,
 * VOICE_COMMUNICATION, ...) and pause Jandal. Jandal's own captures, identified by [ownSessionIds]
 * (the wake detector and the speech-to-text recorders), never pause us unless the audio server silenced one.
 *
 * A release caused by the audio server silencing our capture is held until no other client remains and
 * [SILENCE_BACKOFF_MS] has passed since the silence. The client that silenced us may be on an always-on
 * source, or may not have registered yet. Re-arming as soon as the list looks empty restarts the capture
 * into the same silence, so the hold stops that flap (#2109).
 *
 * [rearmAllowed] is false while a voice session or wake handoff owns the mic; the transition out of
 * contention then does not re-arm, and that owner re-arms on its own terminal event. [rearmMic] must
 * still refuse to take the mic while [paused] is set, because the voice path calls it independently.
 */
internal class WakeMicContentionGate(
    private val releaseMic: () -> Unit,
    private val rearmMic: () -> Unit,
    private val rearmAllowed: () -> Boolean,
    private val ownSessionIds: () -> Set<Int>,
    private val clockMs: () -> Long,
    /** Asks for a later [onRecordingClients] with the current clients, so a time-based hold can end without a callback. */
    private val scheduleRecheck: (delayMs: Long) -> Unit,
) {
    /** True while another app holds the microphone, so the wake detector must not capture. */
    @Volatile var paused: Boolean = false
        private set

    /** Clock time of the release caused by the audio server silencing our capture; null for other pauses. */
    private var silencedAtMs: Long? = null

    /** Called with the full active-client list on every recording-configuration change, and on re-sync. */
    fun onRecordingClients(clients: List<MicRecordingClient>) {
        val own = ownSessionIds()
        val ownClients = clients.filter { it.sessionId in own }
        val others = clients.filter { it.sessionId !in own }
        val silenced = ownClients.any { it.silenced }
        val contended = silenced || others.any { it.source !in ALWAYS_ON_SOURCES }

        if (!paused) {
            if (contended) {
                paused = true
                silencedAtMs = if (silenced) clockMs() else null
                releaseMic()
            }
            return
        }
        if (contended) return
        silencedAtMs?.let { since ->
            // Any remaining client, including an always-on one, may be the silencer: wait for it to go.
            if (others.isNotEmpty()) return
            val remainingMs = since + SILENCE_BACKOFF_MS - clockMs()
            if (remainingMs > 0) {
                scheduleRecheck(remainingMs)
                return
            }
        }
        paused = false
        silencedAtMs = null
        if (rearmAllowed()) rearmMic()
    }

    companion object {
        /** Hold after the audio server silences our capture, before re-arming with no other client present (#2109). */
        const val SILENCE_BACKOFF_MS: Long = 10_000L

        /**
         * The hidden `MediaRecorder.AudioSource.HOTWORD` constant. The SDK does not expose it, so its value is
         * listed here. System hotword clients capture with it and must not pause Jandal (#2109).
         */
        const val HOTWORD_SOURCE: Int = 1999

        /**
         * Sources used by always-on listeners (wake words, hotwords, assistant dictation). A client on one
         * of these never pauses Jandal.
         */
        val ALWAYS_ON_SOURCES: Set<Int> = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, HOTWORD_SOURCE)
    }
}
