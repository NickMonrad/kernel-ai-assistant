package com.kernel.ai.core.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    data class Cancelled(val captureSessionId: Long? = null) : VoiceInputStartResult
}

internal object VoiceCaptureSessionIds {
    private val nextId = AtomicLong()

    fun allocate(): Long = nextId.incrementAndGet()
}

sealed interface VoiceInputEvent {
    val mode: VoiceCaptureMode
    val captureSessionId: Long

    /**
     * A native capture is about to create its recognizer (#2027). The event is emitted before the
     * recognizer is created, so a consumer that holds the microphone (the wake-word detector) is told to
     * release it first. The guarantee is only that order: the release runs asynchronously, and the
     * on-device recognizer opens the mic a moment later. The fix relies on that timing margin, which the
     * S21 hold-to-talk runs measured; it is not a synchronisation guarantee.
     * Ends with ListeningStarted, or with ListeningStopped if the start fails.
     */
    data class ListeningRequested(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
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
    /**
     * The capture ended. With `cancelled = true` it was superseded, discarded, or its engine was released
     * (engine switch or deletion): no result follows, so consumers clear listening and must not submit partial
     * text. With `cancelled = false` it ended after an explicit finalize, and the stop follows a final [Transcript].
     */
    data class ListeningStopped(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
        val cancelled: Boolean = false,
    ) : VoiceInputEvent
    /** End of user speech; the engine is now transcribing (#1850). */
    data class SpeechEnded(
        override val mode: VoiceCaptureMode,
        override val captureSessionId: Long = 0L,
    ) : VoiceInputEvent
}

data class VoiceNotice(val captureSessionId: Long, val message: String)


private val NO_LEVEL: StateFlow<Float> = MutableStateFlow(0f)

interface VoiceInputController {
    val events: Flow<VoiceInputEvent> get() = emptyFlow()

    /**
     * Capture lifecycle events for microphone arbitration (the wake-word detector), including events from
     * a controller that the selector has just replaced. [events] is filtered to the active controller;
     * a replaced native session's close must still reach the arbiter or the wake detector stays released.
     * Consumers outside mic arbitration use [events].
     */
    val micLifecycleEvents: Flow<VoiceInputEvent> get() = events

    /**
     * User-facing notes, such as a Zipformer hand-off after Android native failed. Each note
     * carries the capture session id the consumer received from [startListening], so a surface
     * shows only the notes for its own capture. The latest note is replayed: a note posted
     * while [startListening] is still running reaches a collector that subscribes after it
     * returns, and a mid-session note is in [SharedFlow.replayCache] before the replacement
     * recognizer's first event is delivered. Use [noticesFor] and [latestNoticeFor].
     */
    val notices: SharedFlow<VoiceNotice> get() = NO_NOTICES

    /**
     * Live mic level in 0..1 while a capture is open (#1850). Engines without a level meter
     * keep the default 0, so the UI must not infer activity from it.
     */
    val inputLevel: StateFlow<Float> get() = NO_LEVEL

    suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult

    fun stopListening()

    /**
     * Pre-loads the selected recognizer off the main thread (#1850) so the first tap is quicker.
     * Never opens the microphone. The default is a no-op for engines with nothing to preload.
     */
    suspend fun prewarm() {}

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

/** Controllers that can bind an owner before emitting events expose this admission handshake. */
interface VoiceCaptureAdmissionController {
    suspend fun startListening(
        mode: VoiceCaptureMode,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult

    /**
     * Finalizes only [captureSessionId]: its final result still arrives before its terminal, on every
     * engine. Stale UI owners must never stop a replacement capture.
     */
    fun stopListening(captureSessionId: Long) {}

    /**
     * Discards only [captureSessionId]: late results are dropped and no final result arrives. Never
     * affects a replacement.
     */
    fun cancelListening(captureSessionId: Long) {}

    /** Waits until this capture's recorder and audio focus are released. */
    suspend fun awaitCaptureTeardown(captureSessionId: Long) {}

    /** True while a start is pending or an admitted capture remains active. */
    fun hasCaptureOrStart(): Boolean = false
    /** Emits whether the selector currently owns a pending start or active capture. */
    val captureActivity: StateFlow<Boolean>
}

fun VoiceInputController.stopListening(captureSessionId: Long) {
    (this as? VoiceCaptureAdmissionController)?.stopListening(captureSessionId)
}

fun VoiceInputController.cancelListening(captureSessionId: Long) {
    (this as? VoiceCaptureAdmissionController)?.cancelListening(captureSessionId)
}

fun VoiceInputController.hasCaptureOrStart(): Boolean =
    (this as? VoiceCaptureAdmissionController)?.hasCaptureOrStart() == true

suspend fun VoiceInputController.startListening(
    mode: VoiceCaptureMode,
    onSessionAdmitted: (Long) -> Unit,
): VoiceInputStartResult {
    if (this is VoiceCaptureAdmissionController) {
        return startListening(mode, onSessionAdmitted)
    }
    val result = startListening(mode)
    when (result) {
        is VoiceInputStartResult.Started -> onSessionAdmitted(result.captureSessionId)
        is VoiceInputStartResult.Cancelled -> result.captureSessionId?.let(onSessionAdmitted)
        is VoiceInputStartResult.Unavailable -> Unit
    }
    return result
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
