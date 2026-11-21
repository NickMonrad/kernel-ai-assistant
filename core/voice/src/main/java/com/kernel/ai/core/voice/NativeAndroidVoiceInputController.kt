package com.kernel.ai.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "NativeVoiceInput"
private const val ON_DEVICE_READY_TIMEOUT_MS = 1_500L
/**
 * With no platform fallback the startup timeout ends the session with an error instead of
 * handing over, so allow slower on-device recognizers more time to become ready.
 */
internal const val ON_DEVICE_READY_TIMEOUT_NO_FALLBACK_MS = 5_000L
private const val SESSION_RESULT_TIMEOUT_MS = 6_000L

/**
 * Settle delay between destroying a dead recognizer and creating its replacement in
 * the #1433 in-place no-speech refresh.  A back-to-back destroy() -> create() against
 * the platform recognition service intermittently makes the new instance die
 * immediately with ERROR_CLIENT (observed on the S21); this bound is a recognizer
 * lifecycle settle, not a command delay, and the session watchdog covers the gap.
 */
private const val REFRESH_SETTLE_MS = 300L

/**
 * No-speech budget for a REFRESHED alert recognizer (#1433).  The original
 * recognizer already consumed most of the handoff window before the platform's
 * no-speech timeout; the replacement only needs to cover the remaining command
 * arrival, and must terminate early enough that the whole session re-arms within
 * the acoustic runner's 15 s terminal window (the platform's second no-match is
 * not guaranteed).  Speech progress still extends via the 6 s result timeout.
 */
private const val REFRESHED_SESSION_SILENCE_TIMEOUT_MS = 5_000L

/**
 * Maximum in-place recognizer refreshes per session (#1433).  A single refresh
 * extends the first wake session's window to ~10.5 s (5.1 s platform no-speech +
 * one refreshed 5.1 s window), covering the measured 7.4-9.0 s command arrival,
 * while still bounding a false wake: the next platform no-speech timeout surfaces
 * as a genuine error and the session terminates within the acoustic runner's
 * 15 s terminal window.
 */
private const val MAX_NO_SPEECH_REFRESHES = 1

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

/**
 * Whether a capture start must run the live on-device language check before it starts (#1973).
 * Alert commands and a cached Ready answer never do. With online recognition on, any earlier answer
 * is reused: the platform service covers an on-device recognizer that is not confirmed ready, so
 * checking again on every tap only delays the ready cue (the check can take up to its 5 s timeout,
 * then the 400 ms settle).
 */
internal fun shouldRunLiveLocaleCheck(
    mode: VoiceCaptureMode,
    cachedStatus: AndroidNativeRecognitionLocaleStatus,
    localeChecked: Boolean,
    allowPlatformFallback: Boolean,
): Boolean =
    !shouldUseCachedCaptureAvailability(mode) &&
        cachedStatus != AndroidNativeRecognitionLocaleStatus.Ready &&
        !(allowPlatformFallback && localeChecked)

/**
 * #1973: whether a start goes to the platform service without touching the on-device one, so it waits
 * neither for a running live check nor for that check's #1008 settle. True with online recognition on
 * for an alert command (always platform-first), or for an answered, not-confirmed language when an
 * outside speech service is installed. [hasExternalPlatformRecognizer] is only asked in that last case.
 */
internal fun skipsLiveCheckWaits(
    mode: VoiceCaptureMode,
    allowPlatformFallback: Boolean,
    cachedOnDeviceReady: Boolean,
    localeChecked: Boolean,
    hasExternalPlatformRecognizer: () -> Boolean,
): Boolean = allowPlatformFallback && (
    mode == VoiceCaptureMode.AlertCommand ||
        (!cachedOnDeviceReady && localeChecked && hasExternalPlatformRecognizer())
    )

/**
 * The reason a capture cannot start, or null. When the platform speech service can take the capture
 * ([platformServiceAvailable]: online recognition on and a speech service other than Jandal's own is
 * installed), a missing on-device recognizer or language pack does not block (#1973): the start goes
 * to that service instead of failing over to asr4all.
 */
internal fun captureBlockingReason(
    availability: AndroidNativeRecognitionAvailability,
    platformServiceAvailable: Boolean,
): String? =
    if (platformServiceAvailable && availability.isRecognitionAvailable) null else availability.blockingReason


internal enum class RecognizerBackend {
    OnDevice,
    Platform,
}

internal fun initialRecognizerBackend(
    mode: VoiceCaptureMode,
    allowPlatformFallback: Boolean = true,
    onDeviceReady: Boolean = true,
    platformServiceAvailable: Boolean = true,
): RecognizerBackend = when {
    !allowPlatformFallback -> RecognizerBackend.OnDevice
    // Wake-word / alert flows are cue-driven. Starting on-device first can report ready,
    // play the cue, then silently fall back to platform seconds later on some devices
    // (observed on S23). Start directly on the vetted platform recognizer for alert
    // commands so the cue aligns with the backend that will actually capture speech.
    // When the user has not allowed the online platform service, alert commands stay
    // on the on-device recognizer.
    mode == VoiceCaptureMode.AlertCommand -> RecognizerBackend.Platform
    // #1973: an on-device recognizer that is not confirmed ready for the language would only
    // add its 1.5 s startup timeout before the platform retry, so start on the platform when
    // there is one to start on.
    !onDeviceReady && platformServiceAvailable -> RecognizerBackend.Platform
    else -> RecognizerBackend.OnDevice
}

internal fun shouldRetryWithPlatformAfterStartupTimeout(
    backend: RecognizerBackend,
    allowPlatformFallback: Boolean = true,
): Boolean = backend == RecognizerBackend.OnDevice && allowPlatformFallback

internal fun shouldRetryWithPlatformAfterRecognitionError(
    backend: RecognizerBackend,
    mode: VoiceCaptureMode,
    error: Int,
    heardSpeech: Boolean,
    sawPartialTranscript: Boolean,
    allowPlatformFallback: Boolean = true,
): Boolean =
    allowPlatformFallback &&
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
 * #1433: refresh an alert-session recognizer IN PLACE after its own
 * no-speech timeout (ERROR_NO_MATCH with no speech heard at all).  The platform
 * recognizer's no-speech window (~5 s observed on the S21) is shorter than the
 * cue-to-command handoff, so without the refresh the first wake session would
 * terminate before the command arrives and a retry session would become the
 * normal path.  The refresh reuses the same capture session id and emits no
 * events, so the session-level attempt count and journal correlation are
 * unchanged.  #1008: applies to on-device alert sessions too (used when the online
 * fallback is off); the refresh always restarts on the session's own backend.
 */
internal fun shouldRefreshAlertRecognizerOnNoSpeech(
    @Suppress("UNUSED_PARAMETER") backend: RecognizerBackend,
    mode: VoiceCaptureMode,
    heardSpeech: Boolean,
    sawPartialTranscript: Boolean,
): Boolean =
    mode == VoiceCaptureMode.AlertCommand &&
        !heardSpeech &&
        !sawPartialTranscript

/**
 * Stable error category emitted when an alert session's no-speech window is fully
 * exhausted (in-place refreshes spent and the platform still heard nothing).  The
 * wake-command handoff uses this to skip the session-level retry: the command
 * window has already closed, so a second attempt would only re-wait.  The journal's
 * STT_ERROR category remains the standard "stt_recognition_failed".
 */
const val NO_SPEECH_WINDOW_EXHAUSTED = "no_speech_window_exhausted"

/**
 * Stable categories for on-device failures that the controller can hand to another recognizer
 * (asr4all) without user action: the recognizer never became ready ([NATIVE_NOT_READY]), or
 * its watchdog expired before any speech progress ([NATIVE_STALLED]). Recognizer-reported
 * errors (no match, speech timeout, network) and stalls after speech keep a null category and
 * are surfaced as-is.
 */
const val NATIVE_NOT_READY = "native_not_ready"
const val NATIVE_STALLED = "native_stalled"

internal fun shouldRetryWithPlatformAfterWatchdogTimeout(
    backend: RecognizerBackend,
    sawPartialTranscript: Boolean,
    allowPlatformFallback: Boolean = true,
): Boolean = allowPlatformFallback && backend == RecognizerBackend.OnDevice && !sawPartialTranscript


@Singleton
class NativeAndroidVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val _events = voiceEventFlow()
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()


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

    /** #2029: whether the active session is still wanted by its caller; false once superseded. */
    @Volatile
    private var activeSessionWanted: (() -> Boolean)? = null

    @Volatile
    private var startupFallbackJob: Job? = null

    /**
     * Whether the current capture session may fall back to the platform (possibly online)
     * recognition service. Set per session from the caller; see [startListening].
     */
    @Volatile
    private var allowPlatformFallback: Boolean = false

    /**
     * #1433: owned, cancellable pending in-place recognizer refresh.  Cancelled by
     * [stopListeningInternal], before scheduling a new refresh, and whenever the
     * capture session is replaced; the job re-checks [activeSessionId] after its
     * settle delay so it can never start for a stale session.
     */
    @Volatile
    private var inPlaceRefreshJob: Job? = null

    /** #1433: in-place no-speech refreshes performed within the current capture session. */
    @Volatile
    private var noSpeechRefreshCount = 0

    /**
     * #1433: whether `ListeningStarted` has already been emitted for the current
     * capture session.  An in-place recognizer refresh must not re-emit readiness
     * (the wake-command path has already played its cue and the clock-alert path
     * plays its listening cue per readiness event).  Reset whenever the session is
     * allocated, replaced or terminated.
     */
    @Volatile
    private var readinessEmittedForSession = false
    /** Session whose ListeningRequested was emitted and not yet closed by a terminal event (#2027). */
    @Volatile
    private var announcedSessionId: Long = 0L

    /** #1973: elapsedRealtime when the current session's start was requested, for the start-to-ready timing. */
    @Volatile
    private var sessionStartRequestedAtMs = 0L


    /** Starts an on-device-only session: the platform service is never used. */
    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        startListening(mode, allowPlatformFallback = false)

    /**
     * Starts a capture session. With [allowPlatformFallback] false the session uses only the
     * on-device recognizer; failures surface as errors rather than retrying on the platform
     * recognition service, which may send audio to an online service.
     *
     * #2029: [onSessionAdmitted] receives the session id once it is allocated. [isWanted] is re-checked
     * before each step that opens or retains capture; once false the start is superseded and returns
     * [VoiceInputStartResult.Cancelled] without opening the microphone.
     */
    suspend fun startListening(
        mode: VoiceCaptureMode,
        allowPlatformFallback: Boolean,
        onSessionAdmitted: (Long) -> Unit = {},
        isWanted: () -> Boolean = { true },
    ): VoiceInputStartResult {
        return withContext(Dispatchers.Main.immediate) {
            val startRequestedAtMs = SystemClock.elapsedRealtime()
            if (!isWanted()) return@withContext VoiceInputStartResult.Cancelled()
            // #2027: a replaced session that announced ListeningRequested and never closed still holds the
            // wake mic. It is closed here only if this start fails; a successful start takes ownership.
            val replaced = currentMode?.takeIf { announcedSessionId != 0L }?.let { it to activeSessionId }
            stopListeningInternal(emitStopped = false)
            this@NativeAndroidVoiceInputController.allowPlatformFallback = allowPlatformFallback


            // A live check creates and destroys a throwaway recognizer, and that teardown can kill the
            // shared on-device recognition service just after a session starts (ERROR_SERVER_DISCONNECTED,
            // observed on the S23 Ultra, #1008). A start that can use the on-device service therefore
            // joins a check already running (the chat's prewarm, Settings) rather than repeating it, and
            // waits out its settle. A start that goes to the platform service straight away (#1973) never
            // touches the on-device service, so it waits for neither: see [skipsLiveCheckWaits].
            var cachedAvailability = recognitionSupport.getCaptureAvailability()
            val localeChecked = recognitionSupport.hasLocaleCheckResult(cachedAvailability.languageTag)
            val skipWaits = skipsLiveCheckWaits(
                mode = mode,
                allowPlatformFallback = allowPlatformFallback,
                cachedOnDeviceReady = cachedAvailability.blockingReason == null &&
                    cachedAvailability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready,
                localeChecked = localeChecked,
                hasExternalPlatformRecognizer = recognitionSupport::hasExternalPlatformRecognizer,
            )
            if (!skipWaits) {
                recognitionSupport.awaitInFlightLiveCheck()
                if (!isWanted()) return@withContext supersededStart(replaced)
                cachedAvailability = recognitionSupport.getCaptureAvailability()
            }
            val runLiveCheck = !skipWaits && shouldRunLiveLocaleCheck(
                mode = mode,
                cachedStatus = cachedAvailability.localeStatus,
                localeChecked = recognitionSupport.hasLocaleCheckResult(cachedAvailability.languageTag),
                allowPlatformFallback = allowPlatformFallback,
            )
            val availability = if (runLiveCheck) {
                recognitionSupport.getAvailability().also { delay(LIVE_CHECK_SETTLE_MS) }
            } else {
                if (!skipWaits) {
                    val settleMs = recognitionSupport.liveCheckSettleRemainingMs(LIVE_CHECK_SETTLE_MS)
                    if (settleMs > 0L) delay(settleMs)
                }
                cachedAvailability
            }
            val localeCheckMs = SystemClock.elapsedRealtime() - startRequestedAtMs
            val onDeviceReady = availability.blockingReason == null &&
                availability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
            // Asked only when on-device is not ready, since the platform service is then the alternative.
            val platformServiceAvailable = allowPlatformFallback && !onDeviceReady &&
                recognitionSupport.hasExternalPlatformRecognizer()
            captureBlockingReason(availability, platformServiceAvailable)?.let { reason ->
                Log.w(
                    TAG,
                    "Blocking Android native STT start: language=${availability.languageSummary} " +
                        "localeStatus=${availability.localeStatus} reason=$reason",
                )
                closeReplaced(replaced)
                return@withContext VoiceInputStartResult.Unavailable(
                    reason,
                )
            }

            var admittedSessionId: Long? = null
            try {
                if (!isWanted()) return@withContext supersededStart(replaced)
                requestAudioFocus()
                if (!isWanted()) {
                    releaseAudioFocus()
                    return@withContext supersededStart(replaced)
                }
                val sessionId = VoiceCaptureSessionIds.allocate()
                admittedSessionId = sessionId
                currentMode = mode
                activeSessionId = sessionId
                activeSessionWanted = isWanted
                noSpeechRefreshCount = 0
                readinessEmittedForSession = false
                sessionStartRequestedAtMs = startRequestedAtMs
                onSessionAdmitted(sessionId)
                val startBackend = initialRecognizerBackend(
                    mode = mode,
                    allowPlatformFallback = allowPlatformFallback,
                    onDeviceReady = onDeviceReady,
                    platformServiceAvailable = platformServiceAvailable,
                )
                Log.i(
                    TAG,
                    "STT_START_TIMING start sessionId=$sessionId mode=$mode backend=$startBackend " +
                        "localeStatus=${availability.localeStatus} liveCheck=$runLiveCheck localeCheckMs=$localeCheckMs",
                )
                JandalEvents.record(
                    "voice", "stt_start", "mode" to mode, "backend" to startBackend,
                    "locale" to availability.localeStatus, "check_ms" to localeCheckMs,
                )
                try {
                    if (!isWanted()) return@withContext supersededStart(replaced, sessionId)
                    if (!startRecognizer(
                            sessionId = sessionId,
                            mode = mode,
                            availability = availability,
                            backend = startBackend,
                            isWanted = isWanted,
                        )
                    ) {
                        return@withContext supersededStart(replaced, sessionId)
                    }
                } catch (startEx: Exception) {
                    if (!isWanted()) return@withContext supersededStart(replaced, sessionId)
                    // On-device recognizer failed to start (e.g. not installed, OOM). Clean up
                    // any partially-constructed recognizer before retrying with Platform, so we
                    // don't orphan an instance that could fire stale callbacks.
                    if (startBackend == RecognizerBackend.OnDevice && allowPlatformFallback) {
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
                        if (!isWanted()) return@withContext supersededStart(replaced, sessionId)
                        if (!startRecognizer(
                                sessionId = sessionId,
                                mode = mode,
                                availability = availability,
                                backend = RecognizerBackend.Platform,
                                isWanted = isWanted,
                            )
                        ) {
                            return@withContext supersededStart(replaced, sessionId)
                        }
                    } else {
                        throw startEx
                    }
                }
                VoiceInputStartResult.Started(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Android native voice capture", e)
                // #2027: a start that announced ListeningRequested closes with ListeningStopped so the
                // wake-word detector re-arms. A start that never announced stays silent, as before.
                closeReplaced(replaced)
                admittedSessionId?.let { sessionId ->
                    stopListeningInternal(
                        emitStopped = announcedSessionId == sessionId,
                        cancelled = true,
                        expectedSessionId = sessionId,
                    )
                } ?: releaseAudioFocus()
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

    /** #2029: session-scoped stop, posted to the main executor; a stale session id is a no-op. */
    internal fun stopListeningForSession(captureSessionId: Long) {
        context.mainExecutor.execute {
            stopListeningInternal(emitStopped = true, expectedSessionId = captureSessionId)
        }
    }

    /** #2029: session-scoped cancel for a superseded capture; its Stopped carries cancelled=true. */
    internal fun cancelListeningForSession(captureSessionId: Long) {
        context.mainExecutor.execute {
            stopListeningInternal(
                emitStopped = true,
                expectedSessionId = captureSessionId,
                cancelled = true,
            )
        }
    }

    private fun stopListeningInternal(
        emitStopped: Boolean,
        expectedSessionId: Long? = null,
        cancelled: Boolean = false,
    ) {
        if (expectedSessionId != null && activeSessionId != expectedSessionId) {
            return
        }

        val mode = currentMode
        val sessionId = activeSessionId
        val recognizer = speechRecognizer
        startupFallbackJob?.cancel()
        startupFallbackJob = null
        inPlaceRefreshJob?.cancel()
        inPlaceRefreshJob = null
        speechRecognizer = null
        currentMode = null
        activeSessionWanted = null
        _inputLevel.value = 0f
        activeSessionId = 0L
        readinessEmittedForSession = false
        announcedSessionId = 0L

        recognizer?.apply {
            runCatching { stopListening() }
            runCatching { cancel() }
            runCatching { destroy() }
        }
        releaseAudioFocus()

        if (emitStopped && mode != null) {
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = cancelled))
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

    /**
     * Starts the recognizer for [sessionId]. Returns false, with no recognizer left behind, when [isWanted]
     * turns false before the microphone is opened; the caller then closes the session.
     */
    private fun startRecognizer(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
        backend: RecognizerBackend,
        isRefreshed: Boolean = false,
        isWanted: () -> Boolean = {
            activeSessionId == sessionId && activeSessionWanted?.invoke() == true
        },
    ): Boolean {
        if (!isWanted()) return false
        // #2027: announce the start before any recognizer exists, so the wake-word detector releases the
        // microphone first. Readiness cannot come while another capture holds it.
        _events.tryEmit(VoiceInputEvent.ListeningRequested(mode, sessionId))
        announcedSessionId = sessionId
        val recognizer = when (backend) {
            RecognizerBackend.OnDevice -> recognitionSupport.createOnDeviceSpeechRecognizer()
            RecognizerBackend.Platform -> recognitionSupport.createPlatformSpeechRecognizer()
        }
        if (!isWanted()) {
            runCatching { recognizer.destroy() }
            return false
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
                isRefreshed = isRefreshed,
                mode = mode,
                availability = availability,
                backend = backend,
            ),
        )
        val intent = buildRecognizerIntent(availability)
        if (!isWanted()) return false
        recognizer.startListening(intent)
        scheduleStartupFallback(sessionId, mode, availability, backend)
        return true
    }

    private fun scheduleStartupFallback(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
        backend: RecognizerBackend,
    ) {
        startupFallbackJob?.cancel()
        if (backend != RecognizerBackend.OnDevice) {
            startupFallbackJob = null
            return
        }
        val readyTimeoutMs =
            if (allowPlatformFallback) ON_DEVICE_READY_TIMEOUT_MS else ON_DEVICE_READY_TIMEOUT_NO_FALLBACK_MS
        startupFallbackJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(readyTimeoutMs)
            if (activeSessionId != sessionId) return@launch
            if (!isSessionWanted(sessionId)) {
                closeSuperseded(sessionId)
                return@launch
            }
            if (!shouldRetryWithPlatformAfterStartupTimeout(backend, allowPlatformFallback)) {
                // Online fallback not allowed: end the session instead of leaving it waiting
                // on a recognizer that never became ready.
                Log.w(TAG, "On-device recognizer never became ready for sessionId=$sessionId; platform fallback disabled")
                endNotReadySession(
                    sessionId = sessionId,
                    mode = mode,
                    error = VoiceInputEvent.Error(
                        mode = mode,
                        captureSessionId = sessionId,
                        message = "On-device speech recognition didn't start. Please try again.",
                        category = NATIVE_NOT_READY,
                    ),
                )
                return@launch
            }
            Log.w(
                TAG,
                "On-device recognizer never became ready for sessionId=$sessionId; retrying with platform recognizer",
            )
            // A failed platform retry must end the session too: its ListeningRequested is already out.
            when (retryWithPlatformRecognizer(sessionId = sessionId, mode = mode, availability = availability)) {
                PlatformRetryResult.Started, PlatformRetryResult.Superseded -> Unit
                PlatformRetryResult.Failed -> endNotReadySession(
                    sessionId = sessionId,
                    mode = mode,
                    error = VoiceInputEvent.Error(
                        mode = mode,
                        captureSessionId = sessionId,
                        message = "Android speech recognition failed to start. Please try again.",
                    ),
                )
            }
        }
    }

    /**
     * Ends a session whose recognizer never reached readiness: the terminal [error], then exactly one
     * [VoiceInputEvent.ListeningStopped], the event the wake detector re-arms on (#2027). Callers check
     * that [sessionId] is still active.
     */
    private fun endNotReadySession(sessionId: Long, mode: VoiceCaptureMode, error: VoiceInputEvent.Error) {
        _events.tryEmit(error)
        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
        stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
    }

    /** Emits the close for a replaced session whose start has just failed (#2027); no-op otherwise. */
    private fun closeReplaced(replaced: Pair<VoiceCaptureMode, Long>?) {
        replaced?.let { (mode, sessionId) -> _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId)) }
    }

    /** #2029: the session is still the active one and its caller still wants it. */
    private fun isSessionWanted(sessionId: Long): Boolean =
        activeSessionId == sessionId && activeSessionWanted?.invoke() == true

    /**
     * #2029: ends a superseded session with `ListeningStopped(cancelled = true)`. Only a session that
     * announced ListeningRequested emits it, so the wake detector re-arms only for a closed announcement.
     */
    private fun closeSuperseded(sessionId: Long) {
        stopListeningInternal(
            emitStopped = announcedSessionId == sessionId,
            expectedSessionId = sessionId,
            cancelled = true,
        )
    }

    /** #2029: a start superseded by a newer one. Closes its own admitted session and any replaced one. */
    private fun supersededStart(
        replaced: Pair<VoiceCaptureMode, Long>?,
        sessionId: Long? = null,
    ): VoiceInputStartResult {
        sessionId?.let(::closeSuperseded)
        closeReplaced(replaced)
        return VoiceInputStartResult.Cancelled(sessionId)
    }

    private enum class PlatformRetryResult {
        Started,
        Superseded,
        Failed,
    }

    private fun cancelStartupFallback() {
        startupFallbackJob?.cancel()
        startupFallbackJob = null
    }

    /**
     * #1433: destroy the dead recognizer now and start a replacement on the SAME
     * capture session after a short settle, so the platform recognition service has
     * finished tearing down the old client connection.  The pending refresh is
     * tracked as [inPlaceRefreshJob]: it is cancelled before scheduling another
     * refresh, by [stopListeningInternal], and whenever the session is replaced; it
     * re-checks [activeSessionId] after the settle so it can never start for a stale
     * session.  The replacement re-emits ListeningRequested through [startRecognizer]; the wake gate already
     * holds the yield for this session, so it does not release again.  No ListeningStopped is emitted and no
     * new session id is allocated: the session-level attempt count and journal correlation are unchanged.
     */
    private fun scheduleInPlaceRefresh(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
        backend: RecognizerBackend,
    ) {
        inPlaceRefreshJob?.cancel()
        speechRecognizer?.apply {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        speechRecognizer = null
        var job: Job? = null
        job = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                delay(REFRESH_SETTLE_MS)
                if (activeSessionId != sessionId) return@launch
                val started = startRecognizer(
                    sessionId = sessionId,
                    mode = mode,
                    availability = availability,
                    // Same backend as the session: an on-device session never becomes a
                    // platform (possibly online) one through a refresh.
                    backend = backend,
                    isRefreshed = true,
                )
                if (!started) closeSuperseded(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A genuine refresh-start failure for the still-active session must
                // not strand it: clean up any partially created recognizer and emit
                // exactly one terminal error + stopped event, then clear the session
                // and release audio focus.  The wake-level bounded retry policy then
                // handles this as a genuine startup failure (NOT the exhausted
                // no-speech category).
                if (activeSessionId != sessionId) return@launch
                Log.e(TAG, "In-place refresh failed for sessionId=$sessionId", e)
                speechRecognizer?.apply {
                    runCatching { cancel() }
                    runCatching { destroy() }
                }
                speechRecognizer = null
                _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode = mode,
                        captureSessionId = sessionId,
                        message = e.message ?: "Android speech recognition failed to restart.",
                    ),
                )
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
            } finally {
                if (inPlaceRefreshJob === job) inPlaceRefreshJob = null
            }
        }
        inPlaceRefreshJob = job
    }


    private fun retryWithPlatformRecognizer(
        sessionId: Long,
        mode: VoiceCaptureMode,
        availability: AndroidNativeRecognitionAvailability,
    ): PlatformRetryResult {
        if (activeSessionId != sessionId) return PlatformRetryResult.Superseded
        if (!isSessionWanted(sessionId)) {
            closeSuperseded(sessionId)
            return PlatformRetryResult.Superseded
        }
        if (!allowPlatformFallback) return PlatformRetryResult.Failed

        cancelStartupFallback()
        speechRecognizer?.apply {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        speechRecognizer = null
        return try {
            val started = startRecognizer(
                sessionId = sessionId,
                mode = mode,
                availability = availability,
                backend = RecognizerBackend.Platform,
                isWanted = { isSessionWanted(sessionId) },
            )
            if (started) {
                PlatformRetryResult.Started
            } else {
                closeSuperseded(sessionId)
                PlatformRetryResult.Superseded
            }
        } catch (error: Exception) {
            if (isSessionWanted(sessionId)) {
                Log.e(TAG, "Failed to retry Android native STT with platform recognizer", error)
                PlatformRetryResult.Failed
            } else {
                closeSuperseded(sessionId)
                PlatformRetryResult.Superseded
            }
        }
    }

    private inner class SessionRecognitionListener(
        private val sessionId: Long,
        private val mode: VoiceCaptureMode,
        private val availability: AndroidNativeRecognitionAvailability,
        private val backend: RecognizerBackend,
        private val isRefreshed: Boolean = false,
    ) : RecognitionListener {

        private var sessionCompleted = false
        private var heardSpeech = false
        private var sawPartialTranscript = false
        private var sessionWatchdogJob: Job? = null

        /** #2029: a session superseded by a newer start stops its timers and callbacks without emitting. */
        private fun suppressSupersededWork(): Boolean {
            if (isSessionWanted(sessionId)) return false
            sessionCompleted = true
            cancelSessionWatchdog()
            return true
        }

        private fun resetSessionWatchdog() {
            sessionWatchdogJob?.cancel()
            sessionWatchdogJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch {
                delay(
                    if (isRefreshed && !heardSpeech && !sawPartialTranscript) {
                        REFRESHED_SESSION_SILENCE_TIMEOUT_MS
                    } else {
                        sessionResultTimeoutMs(mode, hasSpeechProgress = heardSpeech || sawPartialTranscript)
                    }
                )
                if (sessionCompleted || activeSessionId != sessionId) return@launch
                if (suppressSupersededWork()) return@launch
                if (
                    shouldRetryWithPlatformAfterWatchdogTimeout(
                        backend = backend,
                        sawPartialTranscript = sawPartialTranscript,
                        allowPlatformFallback = allowPlatformFallback,
                    )
                ) {
                    when (
                        retryWithPlatformRecognizer(
                            sessionId = sessionId,
                            mode = mode,
                            availability = availability,
                        )
                    ) {
                        PlatformRetryResult.Started -> {
                            sessionCompleted = true
                            Log.w(
                                TAG,
                                "Android native STT watchdog retried with platform recognizer for sessionId=$sessionId " +
                                    "mode=$mode backend=$backend heardSpeech=$heardSpeech " +
                                    "sawPartialTranscript=$sawPartialTranscript",
                            )
                            return@launch
                        }
                        PlatformRetryResult.Superseded -> {
                            suppressSupersededWork()
                            return@launch
                        }
                        PlatformRetryResult.Failed -> Unit
                    }
                }
                if (suppressSupersededWork()) return@launch

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
                        // #1433: when the refreshed recognizer's own no-speech watchdog
                        // expires (alert command, refreshed recognizer, no speech, no
                        // partials), the entire command window has elapsed — classify it
                        // as NO_SPEECH_WINDOW_EXHAUSTED so the wake session skips the
                        // pointless attempt-2 and re-arms.  #1853: any other stall before
                        // speech progress is NATIVE_STALLED, which the selectable controller
                        // may hand to asr4all.  Stalls after speech progress stay generic.
                        category = when {
                            heardSpeech || sawPartialTranscript -> null
                            isRefreshed && mode == VoiceCaptureMode.AlertCommand ->
                                NO_SPEECH_WINDOW_EXHAUSTED
                            else -> NATIVE_STALLED
                        },
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
            // #1433: readiness is idempotent per capture session.  An in-place
            // recognizer refresh must not re-emit ListeningStarted (the wake-command
            // path already played its cue, and ClockAlertService plays its listening
            // cue per readiness event).  The first readiness of a session is always
            // emitted — including from a replacement recognizer when the original
            // failed before ever becoming ready.
            if (!readinessEmittedForSession) {
                readinessEmittedForSession = true
                // #1973: start request to the session's first ready (the ready cue follows at once). A
                // platform retry before that first ready shows as backend=Platform with its wait included.
                val startToReadyMs = SystemClock.elapsedRealtime() - sessionStartRequestedAtMs
                Log.i(
                    TAG,
                    "STT_START_TIMING ready sessionId=$sessionId mode=$mode backend=$backend startToReadyMs=$startToReadyMs",
                )
                JandalEvents.record("voice", "stt_ready", "mode" to mode, "backend" to backend, "ms" to startToReadyMs)
                _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
            } else {
                JandalEvents.record("voice", "stt_ready", "mode" to mode, "backend" to backend)
            }
        }

        override fun onBeginningOfSpeech() {
            if (sessionCompleted || activeSessionId != sessionId) return
            heardSpeech = true
            resetSessionWatchdog()
            Log.d(TAG, "Android native STT speech began: sessionId=$sessionId mode=$mode backend=$backend")
            _events.tryEmit(VoiceInputEvent.SpeechDetected(mode, sessionId))
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (sessionCompleted || activeSessionId != sessionId) return
            // Android reports roughly -2..10 dB; normalise to 0..1 for the level meter (#1850).
            _inputLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionCompleted || activeSessionId != sessionId) return
            _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, sessionId))
        }

        override fun onError(error: Int) {
            if (sessionCompleted || activeSessionId != sessionId) return
            if (suppressSupersededWork()) return
            JandalEvents.record("voice", "stt_error", "code" to error)
            if (
                shouldRetryWithPlatformAfterRecognitionError(
                    backend = backend,
                    mode = mode,
                    error = error,
                    heardSpeech = heardSpeech,
                    sawPartialTranscript = sawPartialTranscript,
                    allowPlatformFallback = allowPlatformFallback,
                )
            ) {
                when (
                    retryWithPlatformRecognizer(
                        sessionId = sessionId,
                        mode = mode,
                        availability = availability,
                    )
                ) {
                    PlatformRetryResult.Started -> {
                        sessionCompleted = true
                        Log.w(
                            TAG,
                            "Android native STT retried with platform recognizer after error=$error for sessionId=$sessionId " +
                                "heardSpeech=$heardSpeech sawPartialTranscript=$sawPartialTranscript",
                        )
                        return
                    }
                    PlatformRetryResult.Superseded -> {
                        suppressSupersededWork()
                        return
                    }
                    PlatformRetryResult.Failed -> Unit
                }
            }
            if (suppressSupersededWork()) return
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
                this@NativeAndroidVoiceInputController.noSpeechRefreshCount < MAX_NO_SPEECH_REFRESHES
            ) {
                // The old recognizer is dead; mark this listener terminal so its
                // destroy()'s late ERROR_CLIENT callback cannot surface as a session
                // error, and schedule the in-place replacement.  The refresh budget is
                // bounded (MAX_NO_SPEECH_REFRESHES) so a false wake still terminates.
                this@NativeAndroidVoiceInputController.noSpeechRefreshCount++
                sessionCompleted = true
                scheduleInPlaceRefresh(sessionId, mode, availability, backend)
                Log.w(
                    TAG,
                    "Android native STT refreshed in place after no-speech no-match for sessionId=$sessionId " +
                        "mode=$mode (refresh ${this@NativeAndroidVoiceInputController.noSpeechRefreshCount}/$MAX_NO_SPEECH_REFRESHES)",
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
                    // The no-speech window is fully exhausted (refreshes spent): the
                    // wake-command window has closed, so the session-level retry would
                    // only re-wait.  The journal category stays standard.
                    category = if (
                        error == SpeechRecognizer.ERROR_NO_MATCH &&
                        shouldRefreshAlertRecognizerOnNoSpeech(backend, mode, heardSpeech, sawPartialTranscript)
                    ) {
                        NO_SPEECH_WINDOW_EXHAUSTED
                    } else {
                        null
                    },
                ),
            )
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
            stopListeningInternal(emitStopped = false, expectedSessionId = sessionId)
        }

        override fun onResults(results: Bundle?) {
            if (sessionCompleted || activeSessionId != sessionId) return
            val transcript = extractBestTranscript(results)
            JandalEvents.record("voice", "stt_result", "chars" to transcript.length, "blank" to transcript.isBlank())
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