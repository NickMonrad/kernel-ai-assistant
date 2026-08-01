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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NativeVoiceInput"
private const val ON_DEVICE_READY_TIMEOUT_MS = 1_500L
private const val SESSION_RESULT_TIMEOUT_MS = 6_000L

/**
 * Silence budget for an alert-mode session before any speech progress.
 *
 * Raised from 2.5 s to 10 s by #1433: the wake-command handoff must survive the
 * cue-to-command gap.  Physical evidence (S21 + S23U, diagnostic matrix) shows the
 * wake session reaches readiness, plays the cue, and then receives the command
 * fixture 7.4–9.0 s after readiness (runner cue margin + orchestration latency);
 * the old 2.5 s budget expired the FIRST attempt before the command arrived, forcing
 * a retry session to become the normal path (the observed 7–9 s delay and, on one
 * trial, command playback after the session ended).  10 s matches the legacy Vosk
 * session bound (LISTEN_TIMEOUT_MS) and covers the measured command-arrival latency.
 * The platform recognizer's own shorter no-speech timeout is handled by an in-place
 * recognizer refresh (see [shouldRefreshAlertRecognizerOnNoSpeech]) that keeps the
 * same capture session.  A human responds to the cue within this budget as well.
 * The session still ends promptly on speech progress (6 s result timeout) and on
 * genuine platform errors.
 */
private const val ALERT_SESSION_SILENCE_TIMEOUT_MS = 10_000L

internal fun sessionResultTimeoutMs(
    mode: VoiceCaptureMode,
    hasSpeechProgress: Boolean = false,
): Long =
    if (mode == VoiceCaptureMode.AlertCommand && !hasSpeechProgress) {
        ALERT_SESSION_SILENCE_TIMEOUT_MS
    } else {
        SESSION_RESULT_TIMEOUT_MS
    }



internal fun shouldForceRecognizerLanguage(availability: AndroidNativeRecognitionAvailability): Boolean =
    availability.languageTag.isNotBlank() &&
        availability.localeStatus != AndroidNativeRecognitionLocaleStatus.Unknown


internal fun shouldUseCachedCaptureAvailability(mode: VoiceCaptureMode): Boolean =
    mode == VoiceCaptureMode.AlertCommand


internal enum class RecognizerBackend {
    OnDevice,
    Platform,
}

internal fun initialRecognizerBackend(mode: VoiceCaptureMode): RecognizerBackend =
    if (mode == VoiceCaptureMode.AlertCommand) {
        // Wake-word / alert flows are cue-driven. Starting on-device first can report ready,
        // play the cue, then silently fall back to platform seconds later on some devices
        // (observed on S23). Start directly on the vetted platform recognizer for alert
        // commands so the cue aligns with the backend that will actually capture speech.
        RecognizerBackend.Platform
    } else {
        RecognizerBackend.OnDevice
    }

internal fun shouldRetryWithPlatformAfterStartupTimeout(backend: RecognizerBackend): Boolean =
    backend == RecognizerBackend.OnDevice

internal fun shouldRetryWithPlatformAfterRecognitionError(
    backend: RecognizerBackend,
    mode: VoiceCaptureMode,
    error: Int,
    heardSpeech: Boolean,
    sawPartialTranscript: Boolean,
): Boolean =
    backend == RecognizerBackend.OnDevice &&
        when (error) {
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> true
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> when (mode) {
                VoiceCaptureMode.AlertCommand -> !sawPartialTranscript
                VoiceCaptureMode.Command,
                VoiceCaptureMode.SlotReply,
                -> !heardSpeech && !sawPartialTranscript
            }
            else -> false
        }

/**
 * #1433: refresh an alert-session platform recognizer IN PLACE after its own
 * no-speech timeout (ERROR_NO_MATCH with no speech heard at all).  The platform
 * recognizer's no-speech window (~5 s observed on the S21) is shorter than the
 * cue-to-command handoff, so without the refresh the first wake session would
 * terminate before the command arrives and a retry session would become the
 * normal path.  The refresh reuses the same capture session id and emits no
 * events, so the session-level attempt count and journal correlation are
 * unchanged.
 */
internal fun shouldRefreshAlertRecognizerOnNoSpeech(
    backend: RecognizerBackend,
    mode: VoiceCaptureMode,
    heardSpeech: Boolean,
    sawPartialTranscript: Boolean,
): Boolean =
    backend == RecognizerBackend.Platform &&
        mode == VoiceCaptureMode.AlertCommand &&
        !heardSpeech &&
        !sawPartialTranscript

internal fun shouldRetryWithPlatformAfterWatchdogTimeout(
    backend: RecognizerBackend,
    sawPartialTranscript: Boolean,
): Boolean = backend == RecognizerBackend.OnDevice && !sawPartialTranscript


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
    private var activeSessionId: Long = 0L

    @Volatile
    private var startupFallbackJob: Job? = null



    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        return withContext(Dispatchers.Main.immediate) {
            stopListeningInternal(emitStopped = false)


            val availability = if (shouldUseCachedCaptureAvailability(mode)) {
                recognitionSupport.getCaptureAvailability()
            } else {
                recognitionSupport.getAvailability()
            }
            availability.blockingReason?.let { reason ->
                Log.w(
                    TAG,
                    "Blocking Android native STT start: language=${availability.languageSummary} " +
                        "localeStatus=${availability.localeStatus} reason=$reason",
                )
                return@withContext VoiceInputStartResult.Unavailable(
                    reason,
                )
            }

            try {
                requestAudioFocus()
                val sessionId = VoiceCaptureSessionIds.allocate()
                currentMode = mode
                activeSessionId = sessionId
                val startBackend = initialRecognizerBackend(mode)
                try {
                    startRecognizer(
                        sessionId = sessionId,
                        mode = mode,
                        availability = availability,
                        backend = startBackend,
                    )
                } catch (startEx: Exception) {
                    // On-device recognizer failed to start (e.g. not installed, OOM). Clean up
                    // any partially-constructed recognizer before retrying with Platform, so we
                    // don't orphan an instance that could fire stale callbacks.
                    if (startBackend == RecognizerBackend.OnDevice) {
                        speechRecognizer?.apply {
                            runCatching { cancel() }
                            runCatching { destroy() }
                        }
                        speechRecognizer = null
                        Log.w(
                            TAG,
                            "On-device recognizer failed to start; retrying with platform backend",
                            startEx,
                        )
                        startRecognizer(
                            sessionId = sessionId,
                            mode = mode,
                            availability = availability,
                            backend = RecognizerBackend.Platform,
                        )
                    } else {
                        throw startEx
                    }
                }
                VoiceInputStartResult.Started(sessionId)
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
            stopListeningInternal(emitStopped = true, expectedSessionId = activeSessionId)
        }
    }

    private fun stopListeningInternal(emitStopped: Boolean, expectedSessionId: Long? = null) {
        if (expectedSessionId != null && activeSessionId != expectedSessionId) {
            return
        }

        val mode = currentMode
        val sessionId = activeSessionId
        val recognizer = speechRecognizer
        startupFallbackJob?.cancel()
        startupFallbackJob = null
        speechRecognizer = null
        currentMode = null
        activeSessionId = 0L

        recognizer?.apply {
            runCatching { stopListening() }
            runCatching { cancel() }
            runCatching { destroy() }
        }
        releaseAudioFocus()

        if (emitStopped && mode != null) {
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
        }
    }

    private fun buildRecognizerIntent(availability: AndroidNativeRecognitionAvailability): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (shouldForceRecognizerLanguage(availability)) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, availability.languageTag)
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun startRecognizer(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
        backend: RecognizerBackend,
    ) {
        val recognizer = when (backend) {
            RecognizerBackend.OnDevice -> recognitionSupport.createOnDeviceSpeechRecognizer()
            RecognizerBackend.Platform -> recognitionSupport.createPlatformSpeechRecognizer()
        }
        speechRecognizer = recognizer
        Log.i(
            TAG,
            "Starting Android native STT: sessionId=$sessionId mode=$mode " +
                "backend=$backend language=${availability.languageSummary} " +
                "localeStatus=${availability.localeStatus} " +
                "forceLanguage=${shouldForceRecognizerLanguage(availability)}",
        )
        recognizer.setRecognitionListener(
            SessionRecognitionListener(
                sessionId = sessionId,
                mode = mode,
                availability = availability,
                backend = backend,
            ),
        )
        recognizer.startListening(buildRecognizerIntent(availability))
        scheduleStartupFallback(sessionId, mode, availability, backend)
    }

    private fun scheduleStartupFallback(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
        backend: RecognizerBackend,
    ) {
        startupFallbackJob?.cancel()
        if (!shouldRetryWithPlatformAfterStartupTimeout(backend)) {
            startupFallbackJob = null
            return
        }
        startupFallbackJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(ON_DEVICE_READY_TIMEOUT_MS)
            if (activeSessionId != sessionId) return@launch
            Log.w(
                TAG,
                "On-device recognizer never became ready for sessionId=$sessionId; retrying with platform recognizer",
            )
            retryWithPlatformRecognizer(
                sessionId = sessionId,
                mode = mode,
                availability = availability,
            )
        }
    }

    private fun cancelStartupFallback() {
        startupFallbackJob?.cancel()
        startupFallbackJob = null
    }


    private fun retryWithPlatformRecognizer(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
    ): Boolean {
        if (activeSessionId != sessionId) return false

        return runCatching {
            cancelStartupFallback()
            speechRecognizer?.apply {
                runCatching { cancel() }
                runCatching { destroy() }
            }
            startRecognizer(
                sessionId = sessionId,
                mode = mode,
                availability = availability,
                backend = RecognizerBackend.Platform,
            )
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to retry Android native STT with platform recognizer", error)
            false
        }
    }

    private inner class SessionRecognitionListener(
        private val sessionId: Long,
        private val mode: VoiceCaptureMode,
        private val availability: AndroidNativeRecognitionAvailability,
        private val backend: RecognizerBackend,
    ) : RecognitionListener {

        private var sessionCompleted = false
        private var heardSpeech = false
        private var sawPartialTranscript = false
        private var sessionWatchdogJob: Job? = null

        private fun resetSessionWatchdog() {
            sessionWatchdogJob?.cancel()
            sessionWatchdogJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
                delay(sessionResultTimeoutMs(mode, hasSpeechProgress = heardSpeech || sawPartialTranscript))
                if (sessionCompleted || activeSessionId != sessionId) return@launch
                if (
                    shouldRetryWithPlatformAfterWatchdogTimeout(
                        backend = backend,
                        sawPartialTranscript = sawPartialTranscript,
                    ) &&
                    retryWithPlatformRecognizer(
                        sessionId = sessionId,
                        mode = mode,
                        availability = availability,
                    )
                ) {
                    sessionCompleted = true
                    Log.w(
                        TAG,
                        "Android native STT watchdog retried with platform recognizer for sessionId=$sessionId " +
                            "mode=$mode backend=$backend heardSpeech=$heardSpeech " +
                            "sawPartialTranscript=$sawPartialTranscript",
                    )
                    return@launch
                }

                sessionCompleted = true
                Log.w(
                    TAG,
                    "Android native STT stopped responding before returning a result: " +
                        "sessionId=$sessionId mode=$mode backend=$backend heardSpeech=$heardSpeech " +
                        "sawPartialTranscript=$sawPartialTranscript",
                )
                _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode = mode,
                        captureSessionId = sessionId,
                        message = "Android speech recognition stopped responding before it returned a result.",
                    ),
                )
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
            }
        }

        private fun cancelSessionWatchdog() {
            sessionWatchdogJob?.cancel()
            sessionWatchdogJob = null
        }


        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionCompleted || activeSessionId != sessionId) return
            cancelStartupFallback()
            resetSessionWatchdog()
            Log.i(TAG, "Android native STT ready: sessionId=$sessionId mode=$mode backend=$backend")
            _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
        }

        override fun onBeginningOfSpeech() {
            if (sessionCompleted || activeSessionId != sessionId) return
            heardSpeech = true
            resetSessionWatchdog()
            Log.d(TAG, "Android native STT speech began: sessionId=$sessionId mode=$mode backend=$backend")
            _events.tryEmit(VoiceInputEvent.SpeechDetected(mode, sessionId))
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (sessionCompleted || activeSessionId != sessionId) return
            if (
                shouldRetryWithPlatformAfterRecognitionError(
                    backend = backend,
                    mode = mode,
                    error = error,
                    heardSpeech = heardSpeech,
                    sawPartialTranscript = sawPartialTranscript,
                ) &&
                retryWithPlatformRecognizer(
                    sessionId = sessionId,
                    mode = mode,
                    availability = availability,
                )
            ) {
                sessionCompleted = true
                Log.w(
                    TAG,
                    "Android native STT retried with platform recognizer after error=$error for sessionId=$sessionId " +
                        "heardSpeech=$heardSpeech sawPartialTranscript=$sawPartialTranscript",
                )
                return
            }
            // #1433: the platform recognizer has its own no-speech timeout (~5 s
            // observed on the S21) that fires ERROR_NO_MATCH before the app's
            // alert-session budget when the wake command arrives after the cue
            // (measured 7.4-9.0 s after readiness under the acoustic runner
            // contract).  Refresh the recognizer IN PLACE — same capture session, no
            // new STT_START_REQUESTED — so the first wake session still accepts the
            // command and no attempt-2 session becomes the normal path.  Only for
            // alert sessions that heard nothing at all; any other error still
            // surfaces as a genuine recognition failure.
            if (
                error == SpeechRecognizer.ERROR_NO_MATCH &&
                shouldRefreshAlertRecognizerOnNoSpeech(backend, mode, heardSpeech, sawPartialTranscript) &&
                retryWithPlatformRecognizer(
                    sessionId = sessionId,
                    mode = mode,
                    availability = availability,
                )
            ) {
                Log.w(
                    TAG,
                    "Android native STT refreshed in place after no-speech no-match for sessionId=$sessionId " +
                        "mode=$mode",
                )
                return
            }
            sessionCompleted = true
            cancelStartupFallback()
            cancelSessionWatchdog()
            Log.w(
                TAG,
                "Android native STT error: sessionId=$sessionId mode=$mode error=$error " +
                    "backend=$backend language=${availability.languageSummary} " +
                    "localeStatus=${availability.localeStatus}",
            )
            _events.tryEmit(
                VoiceInputEvent.Error(
                    mode = mode,
                    captureSessionId = sessionId,
                    message = mapError(error, availability),
                ),
            )
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
            stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
        }

        override fun onResults(results: Bundle?) {
            if (sessionCompleted || activeSessionId != sessionId) return
            val transcript = extractBestTranscript(results)
            sessionCompleted = true
            cancelStartupFallback()
            cancelSessionWatchdog()
            Log.i(
                TAG,
                "Android native STT result: sessionId=$sessionId mode=$mode transcript='$transcript' " +
                    "language=${availability.languageSummary}",
            )
            if (transcript.isBlank()) {
                _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode = mode,
                        captureSessionId = sessionId,
                        message = "I didn't catch anything from Android speech recognition.",
                    ),
                )
            } else {
                _events.tryEmit(
                    VoiceInputEvent.Transcript(
                        mode = mode,
                        text = transcript,
                        captureSessionId = sessionId,
                    ),
                )
            }
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
            stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionCompleted || activeSessionId != sessionId) return
            val transcript = extractBestTranscript(partialResults)
            if (transcript.isNotBlank()) {
                sawPartialTranscript = true
                resetSessionWatchdog()
                Log.d(
                    TAG,
                    "Android native STT partial: sessionId=$sessionId mode=$mode transcript='$transcript'",
                )
                _events.tryEmit(
                    VoiceInputEvent.PartialTranscript(
                        mode = mode,
                        text = transcript,
                        captureSessionId = sessionId,
                    ),
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun extractBestTranscript(results: Bundle?): String =
        TranscriptNormaliser.normalise(
            results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
        )

    private fun mapError(
        error: Int,
        availability: AndroidNativeRecognitionAvailability,
    ): String =
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
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "Android speech recognition disconnected unexpectedly while using ${availability.languageSummary}. This can happen when that on-device language is unsupported or not installed."
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