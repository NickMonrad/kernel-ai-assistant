package com.kernel.ai.assistant

import com.kernel.ai.core.voice.VoiceInputEvent

/**
 * Microphone arbitration between [WakeWordService] and the voice capture it yields to (#2027).
 *
 * The wake detector releases its AudioRecord once per voice session, on the first of
 * [VoiceInputEvent.ListeningRequested] or [VoiceInputEvent.ListeningStarted], and re-arms on
 * [VoiceInputEvent.ListeningStopped]. Later yield events in the same session do nothing, so the
 * release runs once, and a re-arm happens only after a release.
 */
internal class WakeMicYieldGate(
    private val releaseMic: () -> Unit,
    private val rearmMic: () -> Unit,
) {
    private var yielded = false

    /**
     * [rearmAllowed] is false while the wake handoff owns the session; the handoff re-arms itself
     * on its own terminal event.
     */
    fun onVoiceEvent(event: VoiceInputEvent, rearmAllowed: Boolean) {
        when (event) {
            is VoiceInputEvent.ListeningRequested,
            is VoiceInputEvent.ListeningStarted,
            -> if (!yielded) {
                yielded = true
                releaseMic()
            }

            is VoiceInputEvent.ListeningStopped -> {
                yielded = false
                if (rearmAllowed) rearmMic()
            }

            else -> Unit
        }
    }
}
