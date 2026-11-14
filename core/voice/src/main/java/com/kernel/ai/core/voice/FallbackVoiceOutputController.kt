package com.kernel.ai.core.voice

import android.util.Log
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.TtsFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Fallback/selector [VoiceOutputController] that respects [VoiceOutputPreferences].
 *
 * - [VoiceOutputEngine.AndroidTts] routes directly to [AndroidTextToSpeechController].
 * - [VoiceOutputEngine.SherpaExperimental] tries [SherpaOnnxVoiceOutputController] first, but
 *   still falls back to Android TTS if Sherpa assets/runtime are missing or if synthesis fails.
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
) : VoiceOutputController {
    private val activeController = MutableStateFlow<VoiceOutputController>(androidTts)

    override val events: Flow<VoiceOutputEvent> = activeController.flatMapLatest { it.events }

    override suspend fun warmUp(): VoiceOutputResult = when (voiceOutputPreferences.selectedEngine.first()) {
        VoiceOutputEngine.AndroidTts -> warmUpAndroidTts()
        VoiceOutputEngine.SherpaExperimental,
        VoiceOutputEngine.KokoroExperimental -> warmUpSherpaOrFallback()
        VoiceOutputEngine.InflectMicroExperimental -> warmUpInflectOrFallback()
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult {
        val engine = voiceOutputPreferences.selectedEngine.first()
        JandalEvents.record("tts", "engine_select", "engine" to engine)
        return when (engine) {
            VoiceOutputEngine.AndroidTts -> {
                activate(androidTts)
                androidTts.speak(request)
            }

            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.KokoroExperimental -> recordedSpeak(engine, request) { speakWithSherpaFallback(request) }
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

            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.KokoroExperimental -> openSherpaStreamingSessionOrFallback(request, engine)
            VoiceOutputEngine.InflectMicroExperimental -> openInflectStreamingSessionOrFallback(request, engine)
        }
    }

    override fun stop() {
        sherpa.stop()
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
            activate(androidTts)
            androidTts.warmUp()
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
            val inflectSession = recordedStreamSession(engine, inflect.openStreamingSession(request))
            return object : VoiceOutputStreamingSession {
                private val bufferedText = StringBuilder()
                private var isClosed = false

                override suspend fun append(
                    text: String,
                    isFinal: Boolean,
                ): VoiceOutputResult {
                    if (isClosed) return VoiceOutputResult.Spoken
                    if (text.isNotBlank()) {
                        // Sentence chunks arrive without a separator; join them with one space so the
                        // Android TTS fallback does not read "degrees.Today's" as one run.
                        if (bufferedText.isNotEmpty() && !bufferedText.last().isWhitespace() && !text.first().isWhitespace()) {
                            bufferedText.append(' ')
                        }
                        bufferedText.append(text)
                    }
                    if (!isFinal) return inflectSession.append(text, isFinal = false)

                    isClosed = true
                    val inflectResult = inflectSession.append(text, isFinal = true)
                    if (inflectResult !is VoiceOutputResult.Unavailable) return inflectResult

                    val finalText = bufferedText.toString().trim()
                    if (finalText.isBlank()) return inflectResult
                    Log.w(
                        TAG,
                        "Inflect Micro TTS streaming synthesis failed (${inflectResult.message}); " +
                            "routing to Android TTS fallback.",
                    )
                    activate(androidTts)
                    val androidWarmUpResult = androidTts.warmUp()
                    if (androidWarmUpResult is VoiceOutputResult.Unavailable) {
                        return androidWarmUpResult
                    }
                    return androidTts.speak(request.copy(text = finalText))
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
            return recordedStreamSession(engine, sherpa.openStreamingSession(request))
        }

        Log.i(
            TAG,
            "Sherpa-ONNX TTS unavailable (${warmUpResult.message}); routing streaming session to Android TTS fallback.",
        )
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

    /** Records streaming speak start, final append and cancel for a non-Android engine (#1870). */
    private fun recordedStreamSession(
        engine: VoiceOutputEngine,
        delegate: VoiceOutputStreamingSession,
    ): VoiceOutputStreamingSession {
        JandalEvents.record("tts", "speak_start", "engine" to engine, "streaming" to true)
        return RecordedStreamingSession(engine, delegate)
    }

    private fun activate(controller: VoiceOutputController) {
        activeController.value = controller
        stopInactiveControllers(controller)
    }

    private class RecordedStreamingSession(
        private val engine: VoiceOutputEngine,
        private val delegate: VoiceOutputStreamingSession,
    ) : VoiceOutputStreamingSession {
        override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
            val result = delegate.append(text, isFinal)
            if (isFinal) {
                val spoken = result is VoiceOutputResult.Spoken
                if (!spoken) JandalEvents.record("tts", "speak_failed", "engine" to engine, "category" to TtsFailure.UNAVAILABLE)
                JandalEvents.record("tts", "speak_end", "engine" to engine, "spoken" to spoken)
            }
            return result
        }

        override suspend fun cancel() {
            delegate.cancel()
            JandalEvents.record("tts", "speak_end", "engine" to engine, "spoken" to false, "cancelled" to true)
        }
    }

    private fun stopInactiveControllers(active: VoiceOutputController) {
        if (active !== sherpa) sherpa.stop()
        if (active !== inflect) inflect.stop()
        if (active !== androidTts) androidTts.stop()
    }
}
