package com.kernel.ai.assistant

/** One active microphone client from `AudioRecordingCallback`, reduced to what arbitration needs (#2108). */
internal data class MicRecordingClient(val sessionId: Int, val silenced: Boolean)

/**
 * Steps wake capture aside while another app records, and re-arms when that ends (#2108).
 *
 * Paused when any client other than Jandal's own capture is recording, or when Jandal's own capture is
 * silenced by the audio server (a higher-priority client took the input). [ownSessionId] is the audio
 * session of the wake detector's live AudioRecord, or null while it holds no capture.
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
        val contended = clients.any { it.sessionId != own || it.silenced }
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
}
