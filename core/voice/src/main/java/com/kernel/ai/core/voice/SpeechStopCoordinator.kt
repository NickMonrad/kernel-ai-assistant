package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks whether Jandal is currently speaking and provides the single user-initiated stop path
 * (#1873) used by the volume-key session and the notification Stop action.
 *
 * [isSpeaking] mirrors [VoiceOutputEvent.SpeakingStarted]/[VoiceOutputEvent.SpeakingStopped].
 * [requestStop] only acts while speaking, so volume keys and the notification have no effect
 * outside speech. It delegates to [VoiceOutputController.stop], which every engine implements
 * by cancelling queued chunks and releasing audio focus.
 */
@Singleton
class SpeechStopCoordinator @Inject constructor(
    private val voiceOutputController: VoiceOutputController,
) {
    private val speaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = speaking.asStateFlow()

    /** Starts mirroring controller events into [isSpeaking] on [scope]. */
    fun observe(scope: CoroutineScope): Job = scope.launch {
        voiceOutputController.events.collect { event -> onEvent(event) }
    }

    internal fun onEvent(event: VoiceOutputEvent) {
        speaking.value = event is VoiceOutputEvent.SpeakingStarted
    }

    /**
     * Stops current speech. Returns false (and does nothing) when Jandal isn't speaking, so the
     * caller can let the key or action fall through.
     */
    fun requestStop(): Boolean {
        if (!speaking.value) return false
        speaking.value = false
        voiceOutputController.stop()
        return true
    }
}
