package com.kernel.ai.core.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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

data class VoiceNotice(val captureSessionId: Long, val message: String)

interface VoiceInputController {
    val events: Flow<VoiceInputEvent> get() = emptyFlow()

    /**
     * User-facing notes, such as a Zipformer hand-off after Android native failed. Each note
     * carries the capture session id the consumer received from [startListening], so a surface
     * shows only the notes for its own capture. The latest note is replayed: a note posted
     * while [startListening] is still running reaches a collector that subscribes after it
     * returns, and a mid-session note is in [SharedFlow.replayCache] before the replacement
     * recognizer's first event is delivered. Use [noticesFor] and [latestNoticeFor].
     */
    val notices: SharedFlow<VoiceNotice> get() = NO_NOTICES

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

private val NO_NOTICES: SharedFlow<VoiceNotice> = MutableSharedFlow<VoiceNotice>().asSharedFlow()

/**
 * Non-blank note messages for one capture session. Collect it right after [startListening]
 * returns [VoiceInputStartResult.Started]; the replayed latest note covers a note posted
 * during the start (#1853, #1866).
 */
fun VoiceInputController.noticesFor(captureSessionId: Long): Flow<String> =
    notices
        .filter { it.captureSessionId == captureSessionId && it.message.isNotBlank() }
        .map { it.message }

/**
 * The note already posted for [captureSessionId], or null. A surface that retries a failed
 * capture checks this when the capture ends: a non-null note means the capture already moved to
 * Zipformer, so retrying would only start the failing recognizer again.
 */
fun VoiceInputController.latestNoticeFor(captureSessionId: Long): String? =
    notices.replayCache.lastOrNull { it.captureSessionId == captureSessionId && it.message.isNotBlank() }?.message

/**
 * Event flow for a controller's recognizer callbacks. The buffer is unbounded, so `tryEmit`
 * never fails and never suspends, and every collector receives every event in emission order,
 * however slow it is. Events emitted while no collector is subscribed are not replayed.
 *
 * The previous `MutableSharedFlow(extraBufferCapacity = 8).tryEmit` returned false and dropped
 * terminal Error/ListeningStopped events whenever a collector fell 8 events behind, which left
 * the UI stalled with no message (#1853). No dispatcher is needed, so constructing a controller
 * does not touch `Dispatchers.Main`.
 */
internal fun voiceEventFlow(): MutableSharedFlow<VoiceInputEvent> =
    MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED)
