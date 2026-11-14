package com.kernel.ai.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
        /**
         * Stable machine category for flow decisions (e.g. skip the session-level
         * retry after the no-speech window is exhausted).  Null = generic error.
         */
        val category: String? = null,
    ) : VoiceInputEvent
    data class ListeningStopped(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
}

interface VoiceInputController {
    val events: Flow<VoiceInputEvent> get() = emptyFlow()

    /**
     * Best-effort user-facing notes, such as a Zipformer hand-off after Android native failed.
     * Not terminal: a note emitted with no collector is not replayed.
     */
    val notices: Flow<String> get() = emptyFlow()

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

/**
 * Ordered, lossless hand-off from a controller's callbacks to its collectors. [tryEmit] queues
 * synchronously and never drops; a pump forwards each event to a [MutableSharedFlow].
 *
 * The previous `MutableSharedFlow(extraBufferCapacity = 8).tryEmit` returned false and dropped
 * terminal Error/ListeningStopped events whenever the buffer was full, which left the UI
 * stalled with no message (#1853).
 */
internal class VoiceEventQueue(scope: CoroutineScope) {
    private val queue = Channel<VoiceInputEvent>(Channel.UNLIMITED)
    private val shared = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)

    val events: Flow<VoiceInputEvent> = shared.asSharedFlow()

    init {
        scope.launch {
            for (event in queue) shared.emit(event)
        }
    }

    fun tryEmit(event: VoiceInputEvent): Boolean = queue.trySend(event).isSuccess
}
