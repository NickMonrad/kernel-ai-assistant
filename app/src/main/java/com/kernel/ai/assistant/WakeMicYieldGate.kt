package com.kernel.ai.assistant

import com.kernel.ai.core.voice.VoiceInputEvent

/**
 * Microphone arbitration between [WakeWordService] and the voice capture it yields to (#2027).
 *
 * The wake detector releases its AudioRecord when a voice session announces itself
 * ([VoiceInputEvent.ListeningRequested]) or starts ([VoiceInputEvent.ListeningStarted]), and re-arms
 * on [VoiceInputEvent.ListeningStopped] of the session that owns the yield. The release runs once per
 * owning session; later yield events of the same session do nothing. A Stopped for a session that does
 * not own the yield is stale (a replaced or failed-over capture) and is ignored, so it cannot re-arm the
 * wake detector under a live capture.
 *
 * [rearmAllowed] is false while the wake handoff owns the session; the handoff re-arms itself on its own
 * terminal event.
 */
internal class WakeMicYieldGate(
    private val releaseMic: () -> Unit,
    private val rearmMic: () -> Unit,
    private val rearmAllowed: () -> Boolean,
) {
    /** Session id that currently holds the yield; null while the wake detector is armed. */
    private var owner: Long? = null

    /** True while a voice session holds the yield, so the wake detector is released. */
    val voiceSessionActive: Boolean get() = owner != null

    fun onVoiceEvent(event: VoiceInputEvent) {
        when (event) {
            is VoiceInputEvent.ListeningRequested,
            is VoiceInputEvent.ListeningStarted,
            -> {
                if (owner == null) releaseMic()
                owner = event.captureSessionId
            }

            is VoiceInputEvent.ListeningStopped -> {
                val current = owner
                if (current != null && current != event.captureSessionId) return
                owner = null
                if (rearmAllowed()) rearmMic()
            }

            else -> Unit
        }
    }
}
