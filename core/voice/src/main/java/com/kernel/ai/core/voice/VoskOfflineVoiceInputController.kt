package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

private const val TAG = "VoskVoiceInput"
private const val MODEL_ASSET_DIR = "vosk-model"
private const val MODEL_TARGET_DIR = "voice"
private const val SAMPLE_RATE = 16000.0f
private const val LISTEN_TIMEOUT_MS = 10_000

@Singleton
class VoskOfflineVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInputController {

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    @Volatile
    private var model: Model? = null

    @Volatile
    private var speechService: SpeechService? = null

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        stopListening()

        val installedModel = ensureModel() ?: return VoiceInputStartResult.Unavailable(
            "Offline voice model is not provisioned yet. Add a Vosk model under app/src/main/assets/$MODEL_ASSET_DIR."
        )

        return withContext(Dispatchers.Main.immediate) {
            try {
                val recognizer = Recognizer(installedModel, SAMPLE_RATE)
                val service = SpeechService(recognizer, SAMPLE_RATE)
                speechService = service
                val listener = SessionRecognitionListener(mode)
                if (!service.startListening(listener, LISTEN_TIMEOUT_MS)) {
                    service.shutdown()
                    speechService = null
                    return@withContext VoiceInputStartResult.Unavailable(
                        "Voice capture is already active."
                    )
                }
                _events.tryEmit(VoiceInputEvent.ListeningStarted(mode))
                VoiceInputStartResult.Started
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vosk voice capture", e)
                speechService?.shutdown()
                speechService = null
                VoiceInputStartResult.Unavailable(
                    e.message ?: "Failed to start offline voice input."
                )
            }
        }
    }

    override fun stopListening() {
        val service = speechService ?: return
        service.stop()
        service.shutdown()
        speechService = null
    }

    private suspend fun ensureModel(): Model? {
        model?.let { return it }

        val hasAssets = withContext(Dispatchers.IO) {
            context.assets.list(MODEL_ASSET_DIR)?.isNotEmpty() == true
        }
        if (!hasAssets) {
            Log.w(TAG, "No Vosk model assets found under $MODEL_ASSET_DIR")
            return null
        }

        val modelPath = withContext(Dispatchers.IO) {
            StorageService.sync(context, MODEL_ASSET_DIR, MODEL_TARGET_DIR)
        }
        return withContext(Dispatchers.IO) {
            Model(modelPath).also { model = it }
        }
    }

    private inner class SessionRecognitionListener(
        private val mode: VoiceCaptureMode,
    ) : RecognitionListener {
        private var completed = false

        override fun onPartialResult(hypothesis: String) = Unit

        override fun onResult(hypothesis: String) {
            completeWithTranscript(hypothesis)
        }

        override fun onFinalResult(hypothesis: String) {
            completeWithTranscript(hypothesis)
            if (!completed) {
                completed = true
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
                speechService = null
            }
        }

        override fun onError(exception: Exception) {
            if (completed) return
            completed = true
            Log.e(TAG, "Vosk recognition failed", exception)
            _events.tryEmit(
                VoiceInputEvent.Error(
                    mode = mode,
                    message = exception.message ?: "Offline voice recognition failed.",
                )
            )
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            speechService = null
        }

        override fun onTimeout() {
            if (completed) return
            completed = true
            _events.tryEmit(
                VoiceInputEvent.Error(
                    mode = mode,
                    message = "I didn't catch anything before listening timed out.",
                )
            )
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            speechService = null
        }

        private fun completeWithTranscript(hypothesis: String) {
            if (completed) return
            val text = parseTranscript(hypothesis)
            if (text.isBlank()) return
            completed = true
            _events.tryEmit(VoiceInputEvent.Transcript(mode = mode, text = text))
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            stopListening()
        }
    }

    private fun parseTranscript(hypothesis: String): String {
        return runCatching {
            JSONObject(hypothesis).optString("text").trim()
        }.getOrDefault("")
    }
}
