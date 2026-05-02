package com.kernel.ai.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

private const val TAG = "NativeVoiceInput"

@Singleton
class NativeAndroidVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var currentMode: VoiceCaptureMode? = null

    @Volatile
    private var sessionCompleted = false

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        return withContext(Dispatchers.Main.immediate) {
            stopListeningInternal(emitStopped = false)

            val availability = recognitionSupport.getAvailability()
            if (!availability.isRecognitionAvailable) {
                return@withContext VoiceInputStartResult.Unavailable(
                    "Android speech recognition is not available on this device.",
                )
            }
            if (!availability.isOnDeviceRecognitionAvailable) {
                return@withContext VoiceInputStartResult.Unavailable(
                    availability.unavailableReason
                        ?: "On-device Android speech recognition is unavailable on this device.",
                )
            }

            try {
                requestAudioFocus()
                val recognizer = recognitionSupport.createOnDeviceSpeechRecognizer()
                currentMode = mode
                sessionCompleted = false
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(SessionRecognitionListener(mode))
                recognizer.startListening(buildRecognizerIntent(availability.languageTag))
                _events.tryEmit(VoiceInputEvent.ListeningStarted(mode))
                VoiceInputStartResult.Started
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Android native voice capture", e)
                stopListeningInternal(emitStopped = false)
                VoiceInputStartResult.Unavailable(
                    e.message ?: "Failed to start Android speech recognition.",
                )
            }
        }
    }

    override fun stopListening() {
        context.mainExecutor.execute {
            stopListeningInternal(emitStopped = true)
        }
    }

    private fun stopListeningInternal(emitStopped: Boolean) {
        val mode = currentMode
        val recognizer = speechRecognizer
        speechRecognizer = null
        currentMode = null
        sessionCompleted = true

        recognizer?.apply {
            runCatching { stopListening() }
            runCatching { cancel() }
            runCatching { destroy() }
        }
        releaseAudioFocus()

        if (emitStopped && mode != null) {
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
        }
    }

    private fun buildRecognizerIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private inner class SessionRecognitionListener(
        private val mode: VoiceCaptureMode,
    ) : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (sessionCompleted) return
            sessionCompleted = true
            _events.tryEmit(
                VoiceInputEvent.Error(
                    mode = mode,
                    message = mapError(error),
                ),
            )
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            stopListeningInternal(emitStopped = false)
        }

        override fun onResults(results: Bundle?) {
            if (sessionCompleted) return
            val transcript = extractBestTranscript(results)
            sessionCompleted = true
            if (transcript.isBlank()) {
                _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode = mode,
                        message = "I didn't catch anything from Android speech recognition.",
                    ),
                )
            } else {
                _events.tryEmit(VoiceInputEvent.Transcript(mode = mode, text = transcript))
            }
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            stopListeningInternal(emitStopped = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionCompleted) return
            val transcript = extractBestTranscript(partialResults)
            if (transcript.isNotBlank()) {
                _events.tryEmit(VoiceInputEvent.PartialTranscript(mode = mode, text = transcript))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun extractBestTranscript(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun mapError(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Android speech recognition had an audio input problem."
            SpeechRecognizer.ERROR_CLIENT -> "Android speech recognition was interrupted by the app."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for Android speech recognition."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "Android speech recognition unexpectedly requested network access or timed out."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The current language is not supported by Android speech recognition."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The current language pack is unavailable for Android speech recognition."
            SpeechRecognizer.ERROR_NO_MATCH -> "Android speech recognition couldn't match what you said."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognition is already busy."
            SpeechRecognizer.ERROR_SERVER -> "Android speech recognition hit a service error."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android speech recognition disconnected unexpectedly."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch anything before Android speech recognition timed out."
            else -> "Android speech recognition failed with error code $error."
        }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
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
