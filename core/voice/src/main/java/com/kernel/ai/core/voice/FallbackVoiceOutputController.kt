package com.kernel.ai.core.voice

import android.util.Log
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
 *
 * Events are forwarded from whichever backend is active via [flatMapLatest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FallbackVoiceOutputController @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val sherpa: SherpaOnnxVoiceOutputController,
    private val androidTts: AndroidTextToSpeechController,
) : VoiceOutputController {
    private val activeController = MutableStateFlow<VoiceOutputController>(androidTts)

    override val events: Flow<VoiceOutputEvent> = activeController.flatMapLatest { it.events }

    override suspend fun warmUp(): VoiceOutputResult = when (voiceOutputPreferences.selectedEngine.first()) {
        VoiceOutputEngine.AndroidTts -> warmUpAndroidTts()
        VoiceOutputEngine.SherpaExperimental,
        VoiceOutputEngine.KokoroExperimental -> warmUpSherpaOrFallback()
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult =
        when (voiceOutputPreferences.selectedEngine.first()) {
            VoiceOutputEngine.AndroidTts -> {
                activate(androidTts)
                androidTts.speak(request)
            }

            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.KokoroExperimental -> speakWithSherpaFallback(request)
        }

    override suspend fun openStreamingSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession =
        when (voiceOutputPreferences.selectedEngine.first()) {
            VoiceOutputEngine.AndroidTts -> {
                activate(androidTts)
                androidTts.openStreamingSession(request)
            }

            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.KokoroExperimental -> openSherpaStreamingSessionOrFallback(request)
        }

    override fun stop() {
        sherpa.stop()
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

    private suspend fun openSherpaStreamingSessionOrFallback(
        request: VoiceSpeakRequest,
    ): VoiceOutputStreamingSession {
        val warmUpResult = sherpa.warmUp()
        if (warmUpResult !is VoiceOutputResult.Unavailable) {
            activate(sherpa)
            return sherpa.openStreamingSession(request)
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

    private fun activate(controller: VoiceOutputController) {
        activeController.value = controller
        stopInactiveControllers(controller)
    }

    private fun stopInactiveControllers(active: VoiceOutputController) {
        if (active !== sherpa) sherpa.stop()
        if (active !== androidTts) androidTts.stop()
    }
}
