package com.kernel.ai.core.voice

import android.util.Log
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.TtsFailure
import com.kernel.ai.core.voice.kokoro.KokoroVoiceOutputController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Fallback/selector [VoiceOutputController] that respects [VoiceOutputPreferences].
 *
 * - [VoiceOutputEngine.AndroidTts] routes directly to [AndroidTextToSpeechController].
 * - [VoiceOutputEngine.SherpaExperimental] tries [SherpaOnnxVoiceOutputController] first, but
 *   still falls back to Android TTS if Sherpa assets/runtime are missing or if synthesis fails.
 * - [VoiceOutputEngine.KokoroExperimental] tries [KokoroVoiceOutputController] first, then Inflect Micro, then Android TTS.
 * - [VoiceOutputEngine.InflectMicroExperimental] tries the Inflect controller first, but
 *   still falls back to Android TTS if the model/frontend/runtime is unavailable.
 * Events are forwarded from whichever backend is active via [flatMapLatest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FallbackVoiceOutputController @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val sherpa: SherpaOnnxVoiceOutputController,
    private val inflect: InflectMicroVoiceOutputController,
    private val androidTts: AndroidTextToSpeechController,
    private val kokoro: KokoroVoiceOutputController,
) : VoiceOutputController, StopReasonAwareVoiceOutput {
    private val activeController = MutableStateFlow<VoiceOutputController>(androidTts)
    @Volatile private var liveStream: RecordedStreamingSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val events: Flow<VoiceOutputEvent> = activeController.flatMapLatest { it.events }

    override suspend fun warmUp(): VoiceOutputResult = when (voiceOutputPreferences.selectedEngine.first()) {
        VoiceOutputEngine.AndroidTts -> warmUpAndroidTts()
        VoiceOutputEngine.SherpaExperimental -> warmUpSherpaOrFallback()
        VoiceOutputEngine.KokoroExperimental -> warmUpKokoroOrFallback()
        VoiceOutputEngine.InflectMicroExperimental -> warmUpInflectOrFallback()
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult {
        // A one-shot reply (slot prompt, widget) replaces a streamed one that is still playing.
        endLiveStream(SpeakEndReason.REPLACED)
        val engine = voiceOutputPreferences.selectedEngine.first()
        JandalEvents.record("tts", "engine_select", "engine" to engine)
        return when (engine) {
            VoiceOutputEngine.AndroidTts -> {
                activate(androidTts)
                androidTts.speak(request)
            }

            VoiceOutputEngine.SherpaExperimental -> recordedSpeak(engine, request) { speakWithSherpaFallback(request) }
            VoiceOutputEngine.KokoroExperimental -> recordedSpeak(engine, request) { speakWithKokoroFallback(request) }
            VoiceOutputEngine.InflectMicroExperimental -> recordedSpeak(engine, request) { speakWithInflectFallback(request) }
        }
    }

    /**
     * Records engine-level speak start/end for the non-Android engines (#1870). The Android path
     * records its own events in [AndroidTextToSpeechController]. Only the engine enum, a length
     * and Booleans are logged; the text is never recorded.
     */
    private suspend inline fun recordedSpeak(
        engine: VoiceOutputEngine,
        request: VoiceSpeakRequest,
        speak: () -> VoiceOutputResult,
    ): VoiceOutputResult {
        JandalEvents.record("tts", "speak_start", "engine" to engine, "chars" to request.text.trim().length)
        val result = speak()
        val spoken = result is VoiceOutputResult.Spoken
        if (!spoken) JandalEvents.record("tts", "speak_failed", "engine" to engine, "category" to TtsFailure.UNAVAILABLE)
        JandalEvents.record("tts", "speak_end", "engine" to engine, "spoken" to spoken)
        return result
    }

    override suspend fun openStreamingSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession {
        val engine = voiceOutputPreferences.selectedEngine.first()
        JandalEvents.record("tts", "engine_select", "engine" to engine)
        return when (engine) {
            VoiceOutputEngine.AndroidTts -> {
                activate(androidTts)
                androidTts.openStreamingSession(request)
            }

            VoiceOutputEngine.SherpaExperimental -> openSherpaStreamingSessionOrFallback(request, engine)
            VoiceOutputEngine.KokoroExperimental -> openKokoroStreamingSessionOrFallback(request, engine)
            VoiceOutputEngine.InflectMicroExperimental -> openInflectStreamingSessionOrFallback(request, engine)
        }
    }

    /** #1923: Sherpa pre-synthesis only; Kokoro and other engines confirm on the normal path. */
    override suspend fun openPreSynthesisedSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession? {
        val engine = voiceOutputPreferences.selectedEngine.first()
        if (engine != VoiceOutputEngine.SherpaExperimental) return null
        if (sherpa.warmUp() is VoiceOutputResult.Unavailable) return null
        activate(sherpa)
        return recordedStreamSession(engine, sherpa, sherpa.openPreSynthesisedSession(request))
    }

    override fun stop() = stopWith(SpeakEndReason.STOPPED)

    override fun stop(reason: SpeechStopReason) = stopWith(reason.toEndReason())

    private fun stopWith(reason: SpeakEndReason) {
        endLiveStream(reason)
        sherpa.stop()
        kokoro.stop()
        inflect.stop()
        androidTts.stop()
    }

    private suspend fun warmUpAndroidTts(): VoiceOutputResult {
        activate(androidTts)
        return androidTts.warmUp()
    }

    private suspend fun warmUpSherpaOrFallback(): VoiceOutputResult {
        val sherpaResult = sherpa.warmUp()
        return if (sherpaResult !is VoiceOutputResult.Unavailable) {
            Log.i(TAG, "Sherpa-ONNX TTS selected as active voice output backend.")
            activate(sherpa)
            sherpaResult
        } else {
            Log.i(
                TAG,
                "Sherpa-ONNX TTS unavailable (${sherpaResult.message}); routing to Android TTS fallback.",
            )
            recordFallback(VoiceOutputEngine.SherpaExperimental)
            activate(androidTts)
            androidTts.warmUp()
        }
    }

    private suspend fun warmUpKokoroOrFallback(): VoiceOutputResult {
        val result = kokoro.warmUp()
        if (result !is VoiceOutputResult.Unavailable) {
            activate(kokoro)
            return result
        }
        val inflectResult = inflect.warmUp()
        if (inflectResult !is VoiceOutputResult.Unavailable) {
            activate(inflect)
            return inflectResult
        }
        activate(androidTts)
        return androidTts.warmUp()
    }

    private suspend fun speakWithKokoroFallback(request: VoiceSpeakRequest): VoiceOutputResult {
        val result = kokoro.warmUp()
        if (result !is VoiceOutputResult.Unavailable) {
            activate(kokoro)
            var handedOff = false
            val spoken = kokoro.speakWithRemainder(request) { text ->
                handedOff = true
                speakWithInflectOrAndroid(request.copy(text = text))
            }
            // Once Kokoro has played audio, a hand-off's result stands: speaking the whole reply again would repeat it.
            if (handedOff || spoken !is VoiceOutputResult.Unavailable) return spoken
        }
        return speakWithInflectOrAndroid(request)
    }

    /** Speaks with Inflect Micro, and with Android TTS only when Inflect Micro is unavailable too (#2117). */
    private suspend fun speakWithInflectOrAndroid(request: VoiceSpeakRequest): VoiceOutputResult {
        val inflectResult = inflect.warmUp()
        if (inflectResult !is VoiceOutputResult.Unavailable) {
            activate(inflect)
            val spoken = inflect.speak(request)
            if (spoken !is VoiceOutputResult.Unavailable) return spoken
        }
        recordFallback(VoiceOutputEngine.KokoroExperimental)
        activate(androidTts)
        val androidResult = androidTts.warmUp()
        return if (androidResult is VoiceOutputResult.Unavailable) androidResult else androidTts.speak(request)
    }

    private suspend fun openKokoroStreamingSessionOrFallback(
        request: VoiceSpeakRequest,
        engine: VoiceOutputEngine,
    ): VoiceOutputStreamingSession {
        val result = kokoro.warmUp()
        if (result !is VoiceOutputResult.Unavailable) {
            activate(kokoro)
            val kokoroSession = recordedStreamSession(
                engine,
                kokoro,
                kokoro.openStreamingSessionWithRemainder(request) { text ->
                    speakWithInflectOrAndroid(request.copy(text = text))
                },
            )
            return kokoroStreamWithInflectFallback(request, kokoroSession)
        }
        return openInflectStreamingSessionOrFallback(request, VoiceOutputEngine.InflectMicroExperimental)
    }

    /**
     * Kokoro reports a reply it could not start only on the final append, and only when none of its audio was queued
     * (VoiceReplyStream). Its buffered text is then unplayed: Inflect Micro speaks it whole, and Android TTS is used only
     * if Inflect Micro is unavailable too (#2117). A chunk that fails after audio has queued hands the rest of the reply
     * to Inflect Micro inside the Kokoro session, so this wrapper never re-speaks it.
     */
    private fun kokoroStreamWithInflectFallback(
        request: VoiceSpeakRequest,
        kokoroSession: VoiceOutputStreamingSession,
    ): VoiceOutputStreamingSession = object : VoiceOutputStreamingSession {
        private val buffer = StreamedReplyBuffer()
        private var isClosed = false

        override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
            if (isClosed) return VoiceOutputResult.Spoken
            buffer.add(text)
            if (!isFinal) return kokoroSession.append(text, isFinal = false)

            isClosed = true
            val kokoroResult = kokoroSession.append(text, isFinal = true)
            if (kokoroResult !is VoiceOutputResult.Unavailable) return kokoroResult
            val finalText = buffer.text()
            if (finalText.isBlank()) return kokoroResult
            Log.w(TAG, "Kokoro streaming synthesis failed (${kokoroResult.message}); routing to Inflect Micro fallback.")
            return speakWithInflectOrAndroid(request.copy(text = finalText))
        }

        override suspend fun appendPause(durationMs: Long) {
            if (!isClosed) kokoroSession.appendPause(durationMs)
        }

        override suspend fun cancel() {
            if (isClosed) return
            isClosed = true
            // Cancellation must not start speaking the buffered text (same rule as the Inflect streaming session).
            kokoroSession.cancel()
        }
    }

    private suspend fun speakWithSherpaFallback(request: VoiceSpeakRequest): VoiceOutputResult {
        val warmUpResult = sherpa.warmUp()
        if (warmUpResult !is VoiceOutputResult.Unavailable) {
            activate(sherpa)
            val sherpaResult = sherpa.speak(request)
            if (sherpaResult !is VoiceOutputResult.Unavailable) {
                return sherpaResult
            }
            Log.w(
                TAG,
                "Sherpa-ONNX TTS speak failed (${sherpaResult.message}); routing to Android TTS fallback.",
            )
        } else {
            Log.i(
                TAG,
                "Sherpa-ONNX TTS unavailable (${warmUpResult.message}); routing to Android TTS fallback.",
            )
        }

        recordFallback(VoiceOutputEngine.SherpaExperimental)
        activate(androidTts)
        val androidWarmUpResult = androidTts.warmUp()
        if (androidWarmUpResult is VoiceOutputResult.Unavailable) {
            return androidWarmUpResult
        }
        return androidTts.speak(request)
    }

    private suspend fun warmUpInflectOrFallback(): VoiceOutputResult {
        val inflectResult = inflect.warmUp()
        return if (inflectResult !is VoiceOutputResult.Unavailable) {
            Log.i(TAG, "Inflect Micro TTS selected as active voice output backend.")
            activate(inflect)
            inflectResult
        } else {
            Log.i(
                TAG,
                "Inflect Micro TTS unavailable (${inflectResult.message}); routing to Android TTS fallback.",
            )
            recordFallback(VoiceOutputEngine.InflectMicroExperimental)
            activate(androidTts)
            androidTts.warmUp()
        }
    }

    private suspend fun speakWithInflectFallback(request: VoiceSpeakRequest): VoiceOutputResult {
        val warmUpResult = inflect.warmUp()
        if (warmUpResult !is VoiceOutputResult.Unavailable) {
            activate(inflect)
            val inflectResult = inflect.speak(request)
            if (inflectResult !is VoiceOutputResult.Unavailable) return inflectResult
            Log.w(
                TAG,
                "Inflect Micro TTS speak failed (${inflectResult.message}); routing to Android TTS fallback.",
            )
        } else {
            Log.i(
                TAG,
                "Inflect Micro TTS unavailable (${warmUpResult.message}); routing to Android TTS fallback.",
            )
        }
        recordFallback(VoiceOutputEngine.InflectMicroExperimental)
        activate(androidTts)
        val androidWarmUpResult = androidTts.warmUp()
        if (androidWarmUpResult is VoiceOutputResult.Unavailable) return androidWarmUpResult
        return androidTts.speak(request)
    }

    private suspend fun openInflectStreamingSessionOrFallback(
        request: VoiceSpeakRequest,
        engine: VoiceOutputEngine,
    ): VoiceOutputStreamingSession {
        val warmUpResult = inflect.warmUp()
        if (warmUpResult !is VoiceOutputResult.Unavailable) {
            activate(inflect)
            val inflectSession = recordedStreamSession(engine, inflect, inflect.openStreamingSession(request))
            return object : VoiceOutputStreamingSession {
                private val buffer = StreamedReplyBuffer()
                private var isClosed = false

                override suspend fun append(
                    text: String,
                    isFinal: Boolean,
                ): VoiceOutputResult {
                    if (isClosed) return VoiceOutputResult.Spoken
                    buffer.add(text)
                    if (!isFinal) return inflectSession.append(text, isFinal = false)

                    isClosed = true
                    val inflectResult = inflectSession.append(text, isFinal = true)
                    if (inflectResult !is VoiceOutputResult.Unavailable) return inflectResult

                    val finalText = buffer.text()
                    if (finalText.isBlank()) return inflectResult
                    Log.w(
                        TAG,
                        "Inflect Micro TTS streaming synthesis failed (${inflectResult.message}); " +
                            "routing to Android TTS fallback.",
                    )
                    recordFallback(VoiceOutputEngine.InflectMicroExperimental)
                    activate(androidTts)
                    val androidWarmUpResult = androidTts.warmUp()
                    if (androidWarmUpResult is VoiceOutputResult.Unavailable) {
                        return androidWarmUpResult
                    }
                    return androidTts.speak(request.copy(text = finalText))
                }

                override suspend fun appendPause(durationMs: Long) {
                    if (!isClosed) inflectSession.appendPause(durationMs)
                }

                override suspend fun cancel() {
                    if (isClosed) return
                    isClosed = true
                    // Close the inner session without the Android TTS fallback — cancellation
                    // must not start speaking the buffered text.
                    inflectSession.cancel()
                }
            }
        }
        Log.i(
            TAG,
            "Inflect Micro TTS unavailable (${warmUpResult.message}); routing streaming session to Android TTS fallback.",
        )
        recordFallback(VoiceOutputEngine.InflectMicroExperimental)
        activate(androidTts)
        val androidWarmUpResult = androidTts.warmUp()
        return if (androidWarmUpResult is VoiceOutputResult.Unavailable) {
            object : VoiceOutputStreamingSession {
                override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult =
                    androidWarmUpResult
            }
        } else {
            androidTts.openStreamingSession(request)
        }
    }

    private suspend fun openSherpaStreamingSessionOrFallback(
        request: VoiceSpeakRequest,
        engine: VoiceOutputEngine,
    ): VoiceOutputStreamingSession {
        val warmUpResult = sherpa.warmUp()
        if (warmUpResult !is VoiceOutputResult.Unavailable) {
            activate(sherpa)
            return recordedStreamSession(engine, sherpa, sherpa.openStreamingSession(request))
        }

        Log.i(
            TAG,
            "Sherpa-ONNX TTS unavailable (${warmUpResult.message}); routing streaming session to Android TTS fallback.",
        )
        recordFallback(VoiceOutputEngine.SherpaExperimental)
        activate(androidTts)
        val androidWarmUpResult = androidTts.warmUp()
        return if (androidWarmUpResult is VoiceOutputResult.Unavailable) {
            object : VoiceOutputStreamingSession {
                override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult =
                    androidWarmUpResult
            }
        } else {
            androidTts.openStreamingSession(request)
        }
    }

    /** Joins streamed sentence chunks for a fallback speak. Chunks arrive without a separator. */
    private class StreamedReplyBuffer {
        private val buffered = StringBuilder()

        fun add(chunk: String) {
            if (chunk.isBlank()) return
            // One space between sentences, so the fallback does not read "degrees.Today's" as one run.
            if (buffered.isNotEmpty() && !buffered.last().isWhitespace() && !chunk.first().isWhitespace()) {
                buffered.append(' ')
            }
            buffered.append(chunk)
        }

        fun text(): String = buffered.toString().trim()
    }

    /** Records streaming speak start, final append and cancel for a non-Android engine (#1870). */
    private fun recordedStreamSession(
        engine: VoiceOutputEngine,
        controller: VoiceOutputController,
        delegate: VoiceOutputStreamingSession,
    ): VoiceOutputStreamingSession {
        liveStream?.abandon(SpeakEndReason.REPLACED)
        JandalEvents.record("tts", "speak_start", "engine" to engine, "streaming" to true)
        return RecordedStreamingSession(engine, delegate, controller.events, scope).also { liveStream = it }
    }

    /** Android TTS fallback, recorded so the export shows which engine failed and why (#1870). */
    private fun recordFallback(from: VoiceOutputEngine) {
        JandalEvents.record("tts", "fallback", "from" to from, "to" to VoiceOutputEngine.AndroidTts, "reason" to TtsFailure.UNAVAILABLE)
    }

    private fun activate(controller: VoiceOutputController) {
        if (controller !== sherpa && controller !== kokoro) endLiveStream(SpeakEndReason.REPLACED)
        activeController.value = controller
        stopInactiveControllers(controller)
    }

    /** Ends the live streamed reply's `speak_end` with [reason] before its playback is torn down. */
    private fun endLiveStream(reason: SpeakEndReason) {
        liveStream?.abandon(reason)
        liveStream = null
    }

    /**
     * Ends the streaming speak event exactly once, when playback ends rather than when the last chunk is
     * queued (#1942), with a [SpeakEndReason]:
     * - an engine that reports its outcome ([ReportsStreamPlaybackOutcome], Sherpa) decides COMPLETED,
     *   ERROR, or STOPPED (a stop that did not come through this controller, e.g. the Settings sample);
     * - other engines end at their SpeakingStarted/SpeakingStopped pair;
     * - a stop, a new reply or an engine switch through this controller ends it first, with its own reason.
     */
    private class RecordedStreamingSession(
        private val engine: VoiceOutputEngine,
        private val delegate: VoiceOutputStreamingSession,
        controllerEvents: Flow<VoiceOutputEvent>,
        scope: CoroutineScope,
    ) : VoiceOutputStreamingSession {
        private val open = AtomicBoolean(true)
        private val outcome = (delegate as? ReportsStreamPlaybackOutcome)?.playbackOutcome

        /**
         * Watches this session's playback; started before any chunk is queued so no event is missed.
         * Nullable: an outcome that is already complete ends the event before the field is assigned.
         */
        private val playbackWatcher: Job? = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (outcome != null) {
                when (outcome.await()) {
                    StreamPlaybackOutcome.COMPLETED -> end(spoken = true, reason = SpeakEndReason.COMPLETED)
                    StreamPlaybackOutcome.CANCELLED -> end(spoken = false, reason = SpeakEndReason.STOPPED)
                    StreamPlaybackOutcome.FAILED -> fail()
                }
                return@launch
            }
            var started = false
            controllerEvents.first { event ->
                if (event is VoiceOutputEvent.SpeakingStarted) started = true
                started && event is VoiceOutputEvent.SpeakingStopped
            }
            end(spoken = true, reason = SpeakEndReason.COMPLETED)
        }

        override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
            val result = delegate.append(text, isFinal)
            if (isFinal && result !is VoiceOutputResult.Spoken) fail()
            return result
        }

        override suspend fun appendPause(durationMs: Long) = delegate.appendPause(durationMs)

        override suspend fun release(request: VoiceSpeakRequest): Boolean = delegate.release(request)

        override suspend fun cancel() {
            delegate.cancel()
            // An outcome-reporting engine keeps playing what was queued; its outcome ends the event.
            if (outcome == null) abandon(SpeakEndReason.STOPPED)
        }

        /** Playback is being torn down (stop, new reply, engine switch), so the event ends now. */
        fun abandon(reason: SpeakEndReason) {
            end(spoken = false, reason = reason)
        }

        private fun fail() {
            if (!open.get()) return
            JandalEvents.record("tts", "speak_failed", "engine" to engine, "category" to TtsFailure.UNAVAILABLE)
            end(spoken = false, reason = SpeakEndReason.ERROR)
        }

        private fun end(spoken: Boolean, reason: SpeakEndReason) {
            if (open.compareAndSet(true, false)) {
                JandalEvents.record("tts", "speak_end", "engine" to engine, "spoken" to spoken, "reason" to reason)
            }
            playbackWatcher?.cancel()
        }
    }

    /**
     * Why a streamed speak ended; logged as `reason=` on `speak_end`. Passed as the enum itself: the event
     * log keeps enum constants and redacts every string. `focus_loss` from #1942 is not a reason: no voice
     * stops on audio-focus loss (Sherpa's focus listener is empty), so it cannot happen.
     */
    private enum class SpeakEndReason {
        COMPLETED,
        STOPPED_BY_USER,
        INTERRUPTED_BACKGROUND,
        /** A new reply, a one-shot speak() or an engine switch replaced it. */
        REPLACED,
        /** A stop with no recorded reason. */
        STOPPED,
        ERROR,
    }

    private fun SpeechStopReason.toEndReason(): SpeakEndReason = when (this) {
        SpeechStopReason.USER -> SpeakEndReason.STOPPED_BY_USER
        SpeechStopReason.BACKGROUND -> SpeakEndReason.INTERRUPTED_BACKGROUND
        SpeechStopReason.NEW_REPLY -> SpeakEndReason.REPLACED
    }

    private fun stopInactiveControllers(active: VoiceOutputController) {
        if (active !== sherpa) sherpa.stop()
        if (active !== kokoro) kokoro.stop()
        if (active !== inflect) inflect.stop()
        if (active !== androidTts) androidTts.stop()
    }
}
