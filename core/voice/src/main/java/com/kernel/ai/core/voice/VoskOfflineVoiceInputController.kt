package com.kernel.ai.core.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
private const val STARTUP_SETTLE_MS = 300L

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

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        stopListening()

        val installedModel = try {
            ensureModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provision Vosk model", e)
            return VoiceInputStartResult.Unavailable(
                e.message ?: "Failed to prepare offline voice input."
            )
        } ?: return VoiceInputStartResult.Unavailable(
            "Offline voice model is not provisioned correctly in this build."
        )

        return withContext(Dispatchers.Main.immediate) {
            try {
                requestAudioFocus()
                val recognizer = Recognizer(installedModel, SAMPLE_RATE)
                val service = SpeechService(recognizer, SAMPLE_RATE)
                speechService = service
                val listener = SessionRecognitionListener(mode)
                if (!service.startListening(listener, LISTEN_TIMEOUT_MS)) {
                    service.shutdown()
                    speechService = null
                    releaseAudioFocus()
                    return@withContext VoiceInputStartResult.Unavailable(
                        "Voice capture is already active."
                    )
                }
                delay(STARTUP_SETTLE_MS)
                _events.tryEmit(VoiceInputEvent.ListeningStarted(mode))
                VoiceInputStartResult.Started
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Vosk voice capture", e)
                speechService?.shutdown()
                speechService = null
                releaseAudioFocus()
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
        releaseAudioFocus()
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

        val hasUuid = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("$MODEL_ASSET_DIR/uuid").close()
                true
            }.getOrDefault(false)
        }
        if (!hasUuid) {
            Log.w(TAG, "Vosk model assets are missing $MODEL_ASSET_DIR/uuid")
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

        override fun onPartialResult(hypothesis: String) {
            if (completed) return
            val text = parsePartialTranscript(hypothesis)
            if (text.isNotBlank()) {
                _events.tryEmit(VoiceInputEvent.PartialTranscript(mode = mode, text = text))
            }
        }

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
            releaseAudioFocus()
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
            releaseAudioFocus()
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

    private fun parsePartialTranscript(hypothesis: String): String {
        return runCatching {
            JSONObject(hypothesis).optString("partial").trim()
        }.getOrDefault("")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
