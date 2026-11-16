package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks whether Jandal is currently speaking and provides the single user-initiated stop path
 * (#1873) used by the volume-key session and the media session Stop control.
 *
 * [isSpeaking] mirrors [VoiceOutputEvent.SpeakingStarted]/[VoiceOutputEvent.SpeakingStopped].
 * [requestStop] only acts while speaking. When a screen collects [stopRequests] it owns the stop
 * (it must also stop output and update its UI state); with no collector, [requestStop] calls
 * [VoiceOutputController.stop] directly.
 */
@Singleton
class SpeechStopCoordinator @Inject constructor(
    private val voiceOutputController: VoiceOutputController,
) {
    private val speaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = speaking.asStateFlow()

    private val stopEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once per accepted stop request while a screen is collecting. */
    val stopRequests: SharedFlow<Unit> = stopEvents.asSharedFlow()

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
        if (!speaking.compareAndSet(expect = true, update = false)) return false
        if (stopEvents.subscriptionCount.value == 0) {
            voiceOutputController.stopFor(SpeechStopReason.USER)
        } else {
            stopEvents.tryEmit(Unit)
        }
        return true
    }
}
