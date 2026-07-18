package com.kernel.ai.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicLong

sealed interface VoiceInputStartResult {
    data class Started(val captureSessionId: Long) : VoiceInputStartResult
    data class Unavailable(val message: String) : VoiceInputStartResult
}

internal object VoiceCaptureSessionIds {
    private val nextId = AtomicLong()

    fun allocate(): Long = nextId.incrementAndGet()
}

sealed interface VoiceInputEvent {
    val mode: VoiceCaptureMode
    val captureSessionId: Long

    data class ListeningStarted(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
    data class SpeechDetected(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
    data class PartialTranscript(
        override val mode: VoiceCaptureMode,
        val text: String,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
    data class Transcript(
        override val mode: VoiceCaptureMode,
        val text: String,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
    data class Error(
        override val mode: VoiceCaptureMode,
        val message: String,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
    data class ListeningStopped(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
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
