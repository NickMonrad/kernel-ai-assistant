package com.kernel.ai.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.SttFailure
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.terms.TermsAcceptancePreferences
import com.kernel.ai.core.voice.asr4all.Asr4AllEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Asr4allSTT"
private const val SAMPLE_RATE = 16_000
private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val LEAD_IN_CHUNKS = 5 // 0.5 s of audio kept before speech onset; load-time silence beyond it is never decoded
private const val ASR4ALL_TIER = "stream_c16r4"
/** Bound on waiting for a capture's job or reader to end; past it the teardown is treated as wedged. */
private const val CAPTURE_JOIN_TIMEOUT_MS = 3_000L
/**
 * Wall-clock limit from mic open to a capture's end: the 20 s audio cap plus 10 s for a cold engine load and the final
 * decode. A stalled mic read or a wedged decode still ends at this bound rather than holding the mic open.
 */
private const val CAPTURE_WALL_LIMIT_MS = 30_000L
/** Pause after a zero-length read, so a reader that gets no samples does not spin the CPU. */
private const val ZERO_READ_PAUSE_MS = 10L

/** Audio focus held by one capture. [request] is null before API 26. */
private class FocusHold(val request: AudioFocusRequest?)

/**
 * One mic open. Owns its pre-roll buffer, recorder, focus hold and reader; nothing here is
 * shared with a later capture, so a later capture can only open the mic after [reader] ends.
 */
private class Capture(
    val sessionId: Long,
    val mode: VoiceCaptureMode,
    val buffer: PreRollAudioBuffer,
    val recorder: AudioRecord,
    val focus: FocusHold?,
    val detector: SpeechDetector,
) {
    lateinit var reader: Job

    /** How the capture ended, recorded once (first end wins) and journaled as stt_endpoint. Null while live. */
    @Volatile var end: CaptureEnd? = null
    /** When speech ended: the first end, if any. Feeds stt_decode_done. */
    @Volatile var endedAtNanos: Long = 0L
    /** Captured audio so far on the captured-audio clock, for the stt_endpoint audio_ms field. */
    @Volatile var capturedMs: Long = 0L
    /** Set by the consumer when the engine returned new text for a chunk; the reader counts that chunk as speech. */
    val decodedSpeech = AtomicBoolean(false)
    /** Set once the consumer has begun its loop, so a watchdog can tell a wedged decode from a cold load. */
    @Volatile var consumerStarted = false
    /** Completed when the consumer job ends, whatever the outcome. */
    val consumerDone = CompletableDeferred<Unit>()
    /** Guards the one ListeningStopped for this capture; the consumer and a stalled-decode release both try. */
    val stopEmitted = AtomicBoolean(false)
    /** Guards the one SpeechEnded for this capture; the reader (endpoint) and the consumer both try. */
    val speechEndedSent = AtomicBoolean(false)
}

/**
 * Why a capture ended. Journaled as `stt_endpoint reason=<NAME>` exactly once per capture. The enum token is the
 * export form: the allowlist (#1870) redacts free strings, so reasons cannot be lower-case text.
 */
internal enum class CaptureEnd {
    /** Endpoint: speech armed, then the trailing silence. */
    VAD,
    /** Endpoint: no armed speech within the no-speech window. */
    NO_SPEECH,
    /** Endpoint: the captured-audio cap. */
    CAP,
    /** Explicit stop: the utterance so far is finalised. */
    STOP,
    /** Cancel: the utterance is discarded. */
    CANCEL,
    /** A newer capture or an engine release supersedes this one. */
    SUPERSEDE,
    /** The AudioRecord read failed or stopped before any endpoint. */
    MIC_ERROR,
    /** The voice detector threw while classifying a chunk. */
    VAD_ERROR,
    /** The engine failed to load; no capture reached the consumer. */
    LOAD_FAILED,
    /** The wall-clock limit passed before the capture ended. */
    WATCHDOG,
    /** The consumer threw while decoding. */
    ENGINE_ERROR,
}

/** Engine identity: a change of size or thread count needs a reopen. */
private data class EngineKey(val size: Asr4AllSize, val threads: Int)

/**
 * Streaming STT with the asr4all model (FUTO), run through [Asr4AllEngine].
 *
 * Capture follows the Sherpa online contract (see [SherpaOnnxVoiceInputController]):
 * - The model files are checked first. A stop, or a [isWanted] that turns false, before the mic
 *   opens ends the start without opening the mic, playing the cue or emitting `ListeningStarted`.
 * - The mic opens (MIC source, speech focus) before the engine loads. The reader fills the
 *   pre-roll buffer while the engine loads, then the capture loop takes it.
 * - Stop closes the pre-roll buffer and stops the recorder at once, so a read blocked in the
 *   recorder returns during a cold load as well as live. The reader then releases its own
 *   recorder and focus.
 *
 * Partials are the running text from [Asr4AllEngine.acceptAudio], emitted when it changes. The
 * engine has no endpoint signal. The reader ends an utterance on captured-audio time ([UtteranceEndpointer]),
 * and the final text is [Asr4AllEngine.finish], decoded after the captured audio has drained.
 *
 * Two ways a capture ends:
 * - [stopListening] is an explicit finalize: the utterance so far is decoded and emitted.
 * - [supersedeCapture] (and [releaseEngine]) discards: nothing decided after it is emitted. A result decided
 *   before it may still be emitted, tagged with the superseded session; consumers filter by sessionId (#2039).
 */
@Singleton
class Asr4allVoiceInputController internal constructor(
    private val context: Context,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val hardwareProfileDetector: HardwareProfileDetector,
    /** Bound on waiting for a capture's job or reader to end; past it the teardown is treated as wedged. */
    private val captureJoinTimeoutMs: Long,
    /** Wall-clock limit from mic open to a capture's end; see [CAPTURE_WALL_LIMIT_MS]. */
    private val captureWallLimitMs: Long,
    /** Captured audio from capture start that cannot arm the endpoint; see [START_CUE_GUARD_MS]. */
    private val cueGuardMs: Long,
    /** Whether the Terms of Use are accepted for the shipped version (#1998). Without it the weights are not used. */
    private val termsAccepted: suspend () -> Boolean,
) : VoiceInputController {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        voiceInputPreferences: VoiceInputPreferences,
        hardwareProfileDetector: HardwareProfileDetector,
        termsAcceptance: TermsAcceptancePreferences,
    ) : this(
        context,
        voiceInputPreferences,
        hardwareProfileDetector,
        CAPTURE_JOIN_TIMEOUT_MS,
        CAPTURE_WALL_LIMIT_MS,
        START_CUE_GUARD_MS,
        { termsAcceptance.record.first().isAccepted() },
    )

    private val _events = voiceEventFlow()
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serialises start, prewarm, release and exclusive file work; guards [engine]. */
    private val startLock = Mutex()
    private var engine: Asr4AllEngine? = null // guarded by startLock
    private var engineKey: EngineKey? = null // guarded by startLock
    private var sileroVad: SileroVad? = null // guarded by startLock; loaded once, kept for the controller's life
    /** Guards the stop count and the mic-open decision, like Sherpa's captureLock. */
    private val captureLock = Any()
    private var stopCount = 0 // guarded by captureLock
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeStop: AtomicBoolean? = null
    @Volatile private var activeDiscard: AtomicBoolean? = null
    /** Most recent mic open; its reader must end before the next mic open or engine release. */
    @Volatile private var lastCapture: Capture? = null
    @Volatile private var activeCapture: Capture? = null
    /** True while [activeCapture] is set. Written under [captureLock] together with [activeCapture]. */
    private val captureActive = MutableStateFlow(false)

    // Test seams (internal, asr4all only). Null in production.
    internal var availableForTest: (() -> Boolean)? = null
    internal var micGrantedForTest: Boolean? = null
    internal var recorderForTest: (() -> AudioRecord?)? = null
    internal var engineOpenForTest: ((Asr4AllSize, Int) -> Asr4AllEngine)? = null
    internal var speechDetectorForTest: (() -> SpeechDetector?)? = null
    internal var releaseFencedForTest: (() -> Unit)? = null
    /** Runs on the caller just before a failed load joins its reader; lets a test cancel the caller at that point. */
    internal var beforeFailedLoadJoinForTest: (() -> Unit)? = null
    /** Runs on the caller just before a cancelled stop joins its reader; lets a test observe the reader still parked. */
    internal var beforeCancelledStopJoinForTest: (() -> Unit)? = null
    /** Runs on the capture job between its final supersede check and its emit; lets a test supersede inside that window. */
    internal var beforeFinalEmitForTest: (() -> Unit)? = null

    /** Weights on disk for the size to load now. */
    private suspend fun modelDownloaded(): Boolean = availableForTest?.invoke()
        ?: withContext(Dispatchers.IO) { Asr4AllInstall.isDownloaded(context, selectedSize()) }

    /** Missing weights and a refused Terms acceptance both read as unavailable, so callers fall back the same way. */
    suspend fun isAvailable(): Boolean = termsAccepted() && modelDownloaded()

    /**
     * True when the Terms refuse the offline model. Nothing downloads without the Terms, so this does not depend on
     * the weights being on disk. Auto and the native fallback then surface the Terms refusal with its Settings
     * action, not another recognizer's message (#2049).
     */
    suspend fun isBlockedByTerms(): Boolean = !termsAccepted()

    /** The size to load now: the user's choice, subject to the tier policy. */
    private suspend fun selectedSize(): Asr4AllSize = Asr4AllInstall.effectiveSize(
        voiceInputPreferences.asr4allSize.first(),
        hardwareProfileDetector.profile.tier,
    )

    /** The capture's speech detector: the test seam, else the Silero model, loaded once and kept (startLock held). */
    private suspend fun speechDetector(): SpeechDetector? {
        speechDetectorForTest?.let { return it() }
        sileroVad?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { SileroVad.load(context.assets) }
                .onFailure { Log.e(TAG, "Silero VAD load failed", it) }
                .getOrNull()
        }
        sileroVad = loaded
        return loaded
    }

    private fun micGranted(): Boolean = micGrantedForTest
        ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        startListening(mode, isWanted = { true })

    /**
     * [isWanted] and the stop count are checked under [captureLock] right before the mic opens.
     * A caller that has been superseded returns false, so no mic opens for it. Once the mic is
     * open, only [supersedeCapture] discards the capture; an explicit stop still finalizes it.
     * [onSession] receives the session id before any event for it is emitted.
     */
    internal suspend fun startListening(
        mode: VoiceCaptureMode,
        isWanted: () -> Boolean,
        onSession: (Long) -> Unit = {},
    ): VoiceInputStartResult {
        val stopsAtCall = synchronized(captureLock) { stopCount }
        return startLock.withLock {
            if (!awaitCaptureEnded()) {
                return@withLock VoiceInputStartResult.Unavailable("The previous microphone session is still closing. Try again.")
            }
            if (!termsAccepted()) {
                return@withLock termsNotAcceptedStart()
            }
            if (!isAvailable()) {
                return@withLock VoiceInputStartResult.Unavailable(
                    "Offline speech model is not downloaded. Download it in Settings → Voice.",
                )
            }
            if (!micGranted()) {
                return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
            }
            val detector = speechDetector() ?: run {
                // The bundled Silero model did not load. Nothing downloads for it, so the message does not ask for one.
                JandalEvents.record("voice", "stt_error", "category" to SttFailure.MODEL_UNAVAILABLE)
                return@withLock VoiceInputStartResult.Unavailable(
                    "Voice detection could not start. Restart the app; if it keeps failing, reinstall it.",
                )
            }
            val sessionId = VoiceCaptureSessionIds.allocate()
            val stop = AtomicBoolean(false)
            val discard = AtomicBoolean(false)
            var stoppedEarly = false
            var micFailed = false
            val capture = synchronized(captureLock) {
                val wanted = isWanted()
                if (!wanted || stopCount != stopsAtCall) {
                    stoppedEarly = true
                    null
                } else {
                    onSession(sessionId)
                    // #2027: announced before the AudioRecord opens so the wake detector can yield. The yield is
                    // asynchronous (tryEmit, then the main-thread router), so it can land after the mic opens; the
                    // owner fence tolerates that overlap. An announced session ends in exactly one ListeningStopped.
                    _events.tryEmit(VoiceInputEvent.ListeningRequested(mode, sessionId))
                    openCapture(sessionId, mode, detector)?.also {
                        lastCapture = it
                        activeCapture = it
                        captureActive.value = true
                        activeStop = stop
                        activeDiscard = discard
                        _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
                    } ?: run { micFailed = true; null }
                }
            }
            if (capture == null) {
                if (stoppedEarly) return@withLock VoiceInputStartResult.Cancelled()
                if (micFailed) _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                check(micFailed)
                return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
            }
            // Bounds the capture from mic open, whatever the reader or the decode is doing (see [watchdog]).
            scope.launch { watchdog(capture, mode, sessionId, discard) }

            // The load runs on the controller scope, so a cancelled caller leaves the await at
            // once and ends the mic before the blocking native open returns. The load is still
            // awaited, non-cancellably, so the start lock is not released while it runs.
            val loading = scope.async(Dispatchers.IO) { ensureEngine() }
            val eng = try {
                loading.await()
            } catch (e: Throwable) {
                halt(capture, CaptureEnd.CANCEL)
                joinReader(capture)
                clearActive(capture)
                capture.consumerDone.complete(Unit)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                withContext(NonCancellable) {
                    loading.join()
                }
                throw e
            }
            if (eng == null) {
                halt(capture, CaptureEnd.LOAD_FAILED)
                beforeFailedLoadJoinForTest?.invoke()
                joinReader(capture)
                clearActive(capture)
                capture.consumerDone.complete(Unit)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                return@withLock VoiceInputStartResult.Unavailable(
                    "Offline speech model could not be loaded. Re-download it in Settings → Voice.",
                )
            }

            // A stop that lands while the model loads ends the capture without a final, matching
            // the Sherpa cold-load contract. Only a capture already attached to a ready engine is
            // finalized by an explicit stop; a supersede discards in every case.
            if (discard.get() || stop.get()) {
                halt(capture, CaptureEnd.STOP)
                beforeCancelledStopJoinForTest?.invoke()
                joinReader(capture)
                clearActive(capture)
                capture.consumerDone.complete(Unit)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                return@withLock VoiceInputStartResult.Cancelled(sessionId)
            }
            capture.consumerStarted = true
            activeJob = scope.launch {
                try {
                    eng.reset()
                    runCapture(capture, eng, mode, sessionId, stop, discard)
                } catch (e: Exception) {
                    Log.e(TAG, "asr4all capture failed", e)
                    endCapture(capture, CaptureEnd.ENGINE_ERROR)
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.AUDIO_LOOP)
                    if (!discard.get()) _events.tryEmit(VoiceInputEvent.Error(mode, e.message ?: "Voice input error", sessionId))
                } finally {
                    capture.buffer.close()
                    joinReader(capture)
                    clearActive(capture)
                    _inputLevel.value = 0f
                    capture.consumerDone.complete(Unit)
                    emitStoppedOnce(capture, mode, sessionId, cancelled = discard.get())
                }
            }
            VoiceInputStartResult.Started(sessionId)
        }
    }

    /** Emits the one ListeningStopped for [c]. The consumer and a stalled-decode release both call it; only the first emits. */
    private fun emitStoppedOnce(c: Capture, mode: VoiceCaptureMode, sessionId: Long, cancelled: Boolean) {
        if (c.stopEmitted.compareAndSet(false, true)) {
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = cancelled))
        }
    }

    /**
     * Wall-clock bound on one capture, from mic open. At [captureWallLimitMs] the capture is halted with
     * [CaptureEnd.WATCHDOG], which ends a stalled mic read. If a consumer has started and still has not ended
     * [captureJoinTimeoutMs] later, its decode is wedged inside the engine. A native call cannot be interrupted, so
     * the caller is released now: the late result is discarded and the session ends with an error.
     */
    private suspend fun watchdog(c: Capture, mode: VoiceCaptureMode, sessionId: Long, discard: AtomicBoolean) {
        if (withTimeoutOrNull(captureWallLimitMs) { c.consumerDone.await() } != null) return
        halt(c, CaptureEnd.WATCHDOG)
        if (withTimeoutOrNull(captureJoinTimeoutMs) { c.consumerDone.await() } != null) return
        // A cold load that is still running is left to the start, which ends the capture when the load returns.
        if (!c.consumerStarted) return
        discard.set(true)
        JandalEvents.record("voice", "stt_error", "category" to SttFailure.DECODE_STALLED)
        Log.e(TAG, "asr4all decode stalled past the wall limit; releasing the caller")
        if (c.stopEmitted.compareAndSet(false, true)) {
            _events.tryEmit(VoiceInputEvent.Error(mode, "Voice input took too long. Try again.", sessionId))
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
        }
    }

    /** Waits for [c]'s reader to exit, giving up after [captureJoinTimeoutMs]; a wedged reader is left running. */
    private suspend fun joinReader(c: Capture) {
        withContext(NonCancellable) { withTimeoutOrNull(captureJoinTimeoutMs) { c.reader.join() } }
    }

    /** Waits for the last capture's job and reader to end. False if either is still running after the bound. */
    private suspend fun awaitCaptureEnded(): Boolean {
        val job = activeJob
        val reader = lastCapture?.reader
        return withTimeoutOrNull(captureJoinTimeoutMs) {
            job?.join()
            reader?.join()
            true
        } ?: false
    }

    /**
     * Opens the mic with its own focus hold and starts its reader. Returns null, with the focus
     * released, when the mic cannot be opened. The reader releases the recorder and this
     * capture's focus on every exit path, and nothing else.
     */
    @SuppressLint("MissingPermission")
    private fun openCapture(sessionId: Long, mode: VoiceCaptureMode, detector: SpeechDetector): Capture? {
        detector.reset()
        var focus: FocusHold? = null
        var recorder: AudioRecord? = null
        return try {
            focus = requestAudioFocus()
            recorder = recorderForTest?.invoke() ?: createAudioRecord()
            if (recorder == null) {
                JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
                releaseAudioFocus(focus)
                return null
            }
            val capture = Capture(sessionId, mode, PreRollAudioBuffer(PreRollAudioBuffer.PRE_ROLL_SAMPLES), recorder, focus, detector)
            capture.reader = scope.launch { readLoop(capture) }
            capture
        } catch (e: Exception) {
            // A throwing AudioRecord or focus request ends the start like a failed open, not as an exception.
            Log.e(TAG, "asr4all mic open failed", e)
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
            recorder?.let(::stopRecorder)
            releaseAudioFocus(focus)
            null
        }
    }

    private fun readLoop(c: Capture) {
        val buf = ShortArray(CHUNK_SAMPLES)
        val leadIn = ArrayDeque<ShortArray>() // the last LEAD_IN_CHUNKS chunks before onset, while the engine is not yet attached
        val endpoint = UtteranceEndpointer(cueGuardSamples = (cueGuardMs * SAMPLE_RATE / 1000).toInt())
        try {
            if (!c.buffer.isClosed) c.recorder.startRecording()
            while (!c.buffer.isClosed) {
                val n = c.recorder.read(buf, 0, buf.size)
                if (n < 0) {
                    // A read error ends the mic without an endpoint; log the code so the early end is diagnosable.
                    Log.w(TAG, "asr4all mic read failed: code=$n")
                    endCapture(c, CaptureEnd.MIC_ERROR)
                    break
                }
                if (n == 0) {
                    Thread.sleep(ZERO_READ_PAUSE_MS)
                    continue
                }
                _inputLevel.value = rmsOf(buf, n).coerceIn(0f, 1f)
                val vad = try {
                    c.detector.isSpeech(buf, n)
                } catch (e: Exception) {
                    Log.e(TAG, "Silero VAD failed", e)
                    endCapture(c, CaptureEnd.VAD_ERROR)
                    break
                }
                // Recognised text is a hint, not a verdict: the endpointer counts it only before the utterance is armed.
                val decoded = c.decodedSpeech.getAndSet(false)
                val wasHeard = endpoint.heardSpeech
                val reason = endpoint.onChunk(n, speech = vad, hint = decoded)
                c.capturedMs = endpoint.capturedMs
                if (endpoint.heardSpeech && !wasHeard) {
                    _events.tryEmit(VoiceInputEvent.SpeechDetected(c.mode, c.sessionId))
                }
                if (c.buffer.isAttached || endpoint.heardSpeech) {
                    // Once the engine is attached every chunk goes to it, so a VAD miss before onset is still decoded.
                    // The kept lead-in goes first, so the engine hears capture order.
                    while (leadIn.isNotEmpty()) {
                        val p = leadIn.removeFirst()
                        c.buffer.append(p, p.size)
                    }
                    c.buffer.append(buf, n)
                } else {
                    // Before the engine attaches, only a bounded lead-in is kept: load-time silence is not decoded.
                    leadIn.addLast(buf.copyOf(n))
                    if (leadIn.size > LEAD_IN_CHUNKS) leadIn.removeFirst()
                }
                if (reason != null) {
                    // Decided on captured audio here, not by the decoder: a slow decode delays the transcript only.
                    // The mic closes now; the consumer still decodes what was captured.
                    if (endpoint.armed) emitSpeechEndedOnce(c)
                    endCapture(c, reason.toCaptureEnd())
                    c.buffer.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pre-roll reader error", e)
            endCapture(c, CaptureEnd.MIC_ERROR)
        } finally {
            // Any exit ends the capture record; a no-op when an end was already recorded.
            endCapture(c, CaptureEnd.MIC_ERROR)
            c.buffer.close()
            stopRecorder(c.recorder)
            releaseAudioFocus(c.focus)
            _inputLevel.value = 0f
        }
    }

    private fun EndpointReason.toCaptureEnd(): CaptureEnd = when (this) {
        EndpointReason.VAD -> CaptureEnd.VAD
        EndpointReason.NO_SPEECH -> CaptureEnd.NO_SPEECH
        EndpointReason.CAP -> CaptureEnd.CAP
    }

    /**
     * Records how [c] ended. The first end wins, so a stop, cancel or watchdog that lands after an endpoint does not
     * rewrite it. Journals `stt_endpoint` exactly once per capture, with the captured-audio length.
     */
    private fun endCapture(c: Capture, end: CaptureEnd) {
        synchronized(c) {
            if (c.end != null) return
            c.end = end
            c.endedAtNanos = System.nanoTime()
        }
        JandalEvents.record("voice", "stt_endpoint", "reason" to end, "audio_ms" to c.capturedMs)
    }

    /**
     * Ends the mic side of [c] as [end]: closes its buffer and stops a recorder that is recording, so a read blocked in
     * the recorder returns. Idempotent. The reader releases the recorder and focus on its own exit; halt must not
     * release it, because the reader may still be inside a read.
     */
    private fun halt(c: Capture, end: CaptureEnd) {
        endCapture(c, end)
        c.buffer.close()
        try {
            if (c.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) c.recorder.stop()
        } catch (_: Exception) {
        }
    }

    /** Emits SpeechEnded for [c] once: the reader at an armed endpoint, or the consumer. */
    private fun emitSpeechEndedOnce(c: Capture) {
        if (c.speechEndedSent.compareAndSet(false, true)) {
            _events.tryEmit(VoiceInputEvent.SpeechEnded(c.mode, c.sessionId))
        }
    }

    /**
     * Drops the controller's pointers to [c] once its consumer has ended. The reader does not clear them: a reader
     * can end on its own endpoint while the final decode still runs, and a release during that decode must find the
     * capture and discard it. Identity-checked, so a later capture is never cleared.
     */
    private fun clearActive(c: Capture) {
        synchronized(captureLock) {
            if (activeCapture !== c) return
            activeCapture = null
            captureActive.value = false
            activeStop = null
            activeDiscard = null
        }
    }

    private fun rmsOf(pcm: ShortArray, n: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until n) {
            val f = pcm[i] / 32768.0
            sumSquares += f * f
        }
        return sqrt(sumSquares / n).toFloat()
    }


    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 2),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
    }

    private fun stopRecorder(recorder: AudioRecord) {
        try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
    }

    private fun requestAudioFocus(): FocusHold? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(req)
            FocusHold(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus({ }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            FocusHold(null)
        }
    }

    private fun releaseAudioFocus(hold: FocusHold?) {
        if (hold == null) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hold.request?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * The engine for the selected size and tier, opened if needed. A changed size or thread count
     * closes the old engine first. The caller must already have ended any previous capture, because
     * its reader calls the engine: startListening and prewarmIfCurrent await that under [startLock],
     * and withEngineReleased ends it before closing. Callers hold [startLock].
     */
    private suspend fun ensureEngine(): Asr4AllEngine? {
        // No weights are loaded without the Terms acceptance (#1998). An open engine is closed the same way.
        if (!termsAccepted()) {
            engine?.close()
            engine = null
            return null
        }
        val tier = hardwareProfileDetector.profile.tier
        val size = Asr4AllInstall.effectiveSize(voiceInputPreferences.asr4allSize.first(), tier)
        val key = EngineKey(size, Asr4AllInstall.threadsFor(tier))
        engine?.takeIf { engineKey == key }?.let { return it }
        engine?.close()
        engine = null
        return openOrNull(key)?.also {
            engine = it
            engineKey = key
            Log.i(TAG, "asr4all engine loaded size=${key.size} threads=${key.threads}")
        }
    }

    private fun open(key: EngineKey): Asr4AllEngine = engineOpenForTest?.invoke(key.size, key.threads)
        ?: Asr4AllEngine.open(Asr4AllInstall.root(context, key.size), key.size, tierName = ASR4ALL_TIER, threads = key.threads)

    /** Null on a failed open (bad or partial model files). Cancellation is rethrown. */
    private fun openOrNull(key: EngineKey): Asr4AllEngine? = try {
        open(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "asr4all engine failed to open", e)
        JandalEvents.record("voice", "stt_error", "category" to SttFailure.MODEL_UNAVAILABLE)
        null
    }

    /**
     * Runs the capture loop over the pre-roll chunks, then live audio, until the buffer ends. The reader ends the
     * buffer at its endpoint (see [Capture.end]) or when the mic stops; an explicit stop drains what was
     * already captured. A discard ends it at once with nothing emitted.
     */
    private fun runCapture(
        c: Capture,
        eng: Asr4AllEngine,
        mode: VoiceCaptureMode,
        sessionId: Long,
        stop: AtomicBoolean,
        discard: AtomicBoolean,
    ) {
        val preRoll = ArrayDeque(c.buffer.attach())
        var textHeard = false // the engine has produced nonempty partial text
        var partial = ""
        while (!discard.get()) {
            val pcm = nextCaptureChunk(preRoll, c.buffer) ?: break
            val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
            // The engine returns its running text; emit only when it changes.
            val text = eng.acceptAudio(floats).trim()
            if (text.isNotEmpty()) {
                textHeard = true
                // A change of the running text counts as a hint. Before the utterance is armed it can rescue a VAD miss;
                // after it, the endpoint follows the VAD alone (see UtteranceEndpointer.onChunk).
                if (text != partial) c.decodedSpeech.set(true)
            }
            if (text.isNotEmpty() && text != partial) {
                partial = text
                if (!discard.get()) _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, text, sessionId))
            }
        }
        // Superseded: no finish, no error, no transcript.
        if (discard.get()) return
        if (textHeard) emitSpeechEndedOnce(c)
        val text = TranscriptNormaliser.stripTrailingSentencePunctuation(eng.finish().trim())
        if (c.endedAtNanos != 0L) {
            val end = c.end
            JandalEvents.record(
                "voice",
                "stt_decode_done",
                "ms" to (System.nanoTime() - c.endedAtNanos) / 1_000_000L,
                "endpointed" to (end == CaptureEnd.VAD || end == CaptureEnd.NO_SPEECH || end == CaptureEnd.CAP),
            )
        }
        // A release may arrive during the final decode; a superseded transcript is dropped.
        if (discard.get()) return
        beforeFinalEmitForTest?.invoke()
        if (text.isNotEmpty()) {
            JandalEvents.record("voice", "stt_result", "chars" to text.length)
            _events.tryEmit(VoiceInputEvent.Transcript(mode, text, sessionId))
        } else if (!stop.get()) {
            // An explicit stop with nothing decoded ends without an error, as before.
            when (c.end) {
                // The mic ended without an endpoint: a real early end of the mic (#2059).
                CaptureEnd.MIC_ERROR -> {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "Microphone stopped. Try again.",
                            captureSessionId = sessionId,
                        ),
                    )
                }
                // The detector threw: a voice-detection failure, not a microphone or recognition failure.
                CaptureEnd.VAD_ERROR -> {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.VAD_UNAVAILABLE)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "Voice detection failed. Try again.",
                            captureSessionId = sessionId,
                        ),
                    )
                }
                // The wall-clock limit ended the capture before any text: a timeout, not a recognition failure.
                CaptureEnd.WATCHDOG -> _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode = mode,
                        message = "Voice input took too long. Try again.",
                        captureSessionId = sessionId,
                    ),
                )
                // Only the no-speech window ends a capture with no decoded text. Silence is not a recognizer
                // failure. The category feeds WakeWordService's wake no-retry gate (#2051); Command retries once (#2070).
                CaptureEnd.NO_SPEECH -> if (!textHeard) {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "No speech heard. Try again.",
                            captureSessionId = sessionId,
                            category = NO_SPEECH_WINDOW_EXHAUSTED,
                        ),
                    )
                } else {
                    emitEmptyFinal(mode, sessionId)
                }
                // Text was heard, the utterance endpointed or hit the cap, and the final decode is empty. This is a
                // failed recognition, not a microphone failure. The export records EMPTY_FINAL; the Error carries no
                // category, so the normal single retry applies.
                else -> emitEmptyFinal(mode, sessionId)
            }
        }
    }

    private fun emitEmptyFinal(mode: VoiceCaptureMode, sessionId: Long) {
        JandalEvents.record("voice", "stt_error", "category" to SttFailure.EMPTY_FINAL)
        _events.tryEmit(
            VoiceInputEvent.Error(
                mode = mode,
                message = "Didn't catch that. Try again.",
                captureSessionId = sessionId,
            ),
        )
    }

    /** Explicit finalize: ends the mic now; the captured audio is decoded and emitted. */
    override fun stopListening() {
        synchronized(captureLock) {
            stopCount++
            activeStop?.set(true)
            activeCapture?.let { halt(it, CaptureEnd.STOP) }
        }
    }
    internal fun stopListeningForSession(captureSessionId: Long) {
        synchronized(captureLock) {
            val capture = activeCapture?.takeIf { it.sessionId == captureSessionId } ?: return
            stopCount++
            activeStop?.set(true)
            halt(capture, CaptureEnd.STOP)
        }
    }

    internal fun cancelListeningForSession(captureSessionId: Long) {
        synchronized(captureLock) {
            val capture = activeCapture?.takeIf { it.sessionId == captureSessionId } ?: return
            stopCount++
            activeDiscard?.set(true)
            activeStop?.set(true)
            halt(capture, CaptureEnd.CANCEL)
        }
    }

    internal fun hasActiveCapture(): Boolean =
        synchronized(captureLock) { activeCapture != null }

    internal suspend fun prewarmIfCurrent(isCurrent: suspend () -> Boolean): Boolean {
        if (!isAvailable()) return false
        // A live capture would make this wait for its end while the caller holds its start mutex.
        if (hasActiveCapture()) return false
        return startLock.withLock {
            if (!isCurrent()) return@withLock false
            if (hasActiveCapture() || !awaitCaptureEnded()) return@withLock false
            withContext(Dispatchers.IO) { ensureEngine() }
            true
        }
    }

    override suspend fun prewarm() {
        prewarmIfCurrent { true }
    }

    /**
     * Discards the current capture, if any, and ends its mic now. A result decided before this call may still be
     * emitted, tagged with the superseded session. Callers that begin a newer start or switch engines call this
     * before waiting for the start lock.
     */
    fun supersedeCapture(): Unit = synchronized(captureLock) {
        supersedeCaptureLocked()
    }

    private fun supersedeCaptureLocked() {
        stopCount++
        activeDiscard?.set(true)
        activeStop?.set(true)
        activeCapture?.let { halt(it, CaptureEnd.SUPERSEDE) }
    }

    /**
     * Supersedes any capture without emitting its result, waits for its job and reader to end,
     * and closes the engine. Idempotent; a later start reopens the engine.
     */
    suspend fun releaseEngine() {
        withEngineReleased {}
    }

    /**
     * Runs [block] with no capture and no engine open: the capture is discarded, its job and
     * reader have ended, and the engine is closed, all under the start lock. [block] runs before
     * the lock is released, so a state change it makes (such as the stored size) is visible to
     * every start that follows. Use this before deleting model files so no start can open them
     * during the deletion.
     *
     * Returns null without running [block] if the capture is still running after
     * [captureJoinTimeoutMs]; the engine stays open in that case.
     */
    suspend fun <T> withEngineReleased(block: suspend () -> T): T? {
        supersedeCapture()
        releaseFencedForTest?.invoke()
        return startLock.withLock {
            supersedeCapture()
            if (!awaitCaptureEnded()) return@withLock null
            withContext(Dispatchers.IO) {
                if (engine != null) Log.i(TAG, "asr4all engine released")
                engine?.close()
                engine = null
            }
            block()
        }
    }

    /** Suspends while a capture is live; returns at once when none is. */
    internal suspend fun awaitCaptureIdle() {
        captureActive.first { !it }
    }

    /**
     * Like [withEngineReleased], but never supersedes a capture: returns null without running [block]
     * while a capture is live or still ending past [captureJoinTimeoutMs]. The check runs under
     * [startLock], and every capture starts under it, so no capture can begin between the check and
     * [block].
     */
    internal suspend fun <T> withEngineReleasedIfIdle(block: suspend () -> T): T? = startLock.withLock {
        if (hasActiveCapture() || !awaitCaptureEnded()) return@withLock null
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
        }
        block()
    }
}
