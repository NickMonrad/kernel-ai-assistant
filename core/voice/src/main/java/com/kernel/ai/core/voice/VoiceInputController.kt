package com.kernel.ai.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface VoiceInputStartResult {
    object Started : VoiceInputStartResult
    data class Unavailable(val message: String) : VoiceInputStartResult
}

sealed interface VoiceInputEvent {
    val mode: VoiceCaptureMode

    data class ListeningStarted(override val mode: VoiceCaptureMode) : VoiceInputEvent
    data class SpeechDetected(override val mode: VoiceCaptureMode) : VoiceInputEvent
    data class PartialTranscript(
        override val mode: VoiceCaptureMode,
        val text: String,
    ) : VoiceInputEvent
    data class Transcript(override val mode: VoiceCaptureMode, val text: String) : VoiceInputEvent
    data class Error(override val mode: VoiceCaptureMode, val message: String) : VoiceInputEvent
    data class ListeningStopped(override val mode: VoiceCaptureMode) : VoiceInputEvent
}

interface VoiceInputController {
    val events: Flow<VoiceInputEvent> get() = emptyFlow()

    suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult

    fun stopListening()

    /**
     * Transcribes [pcm] audio data synchronously and returns the transcript,
     * or `null` when the engine does not support wake-word verification or
     * transcription fails.
     *
     * Engines that need to perform async work (e.g. awaiting a mutex) can
     * override this as `suspend` — callers in coroutine-unsafe contexts
     * (e.g. [WakeWordService]'s verifyWindow) must wrap the call in
     * [kotlinx.coroutines.runBlocking] when calling a [suspend] override.
     */
    suspend fun transcribeBlocking(pcm: ShortArray): String? = null
}
