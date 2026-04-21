package com.kernel.ai.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface VoiceInputStartResult {
    object Started : VoiceInputStartResult
    data class Unavailable(val message: String) : VoiceInputStartResult
}

sealed interface VoiceInputEvent {
    data class ListeningStarted(val mode: VoiceCaptureMode) : VoiceInputEvent
    data class PartialTranscript(val mode: VoiceCaptureMode, val text: String) : VoiceInputEvent
    data class Transcript(val mode: VoiceCaptureMode, val text: String) : VoiceInputEvent
    data class Error(val mode: VoiceCaptureMode, val message: String) : VoiceInputEvent
    data class ListeningStopped(val mode: VoiceCaptureMode) : VoiceInputEvent
}

interface VoiceInputController {
    val events: Flow<VoiceInputEvent> get() = emptyFlow()

    suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult

    fun stopListening()
}
