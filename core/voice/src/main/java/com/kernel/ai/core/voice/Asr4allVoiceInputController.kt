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
private const val SPEECH_RMS_MIN = 0.003f // speech floor in a very quiet room
private const val SPEECH_NOISE_MARGIN = 2f // speech is at least this multiple of the quietest recent chunk
private const val NOISE_WINDOW_CHUNKS = 30 // 3 s of chunk RMS for the quietest-level estimate
private const val NO_SPEECH_TIMEOUT_CHUNKS = 100 // 10 s without decoded text
private const val ENDPOINT_SILENCE_CHUNKS = 10 // 1.0 s without speech after decoded text
private const val MAX_UTTERANCE_CHUNKS = 600 // 60 s hard cap
private const val ASR4ALL_TIER = "stream_c16r4"
/** Bound on waiting for a capture's job or reader to end; past it the teardown is treated as wedged. */
private const val CAPTURE_JOIN_TIMEOUT_MS = 3_000L

/** Audio focus held by one capture. [request] is null before API 26. */
private class FocusHold(val request: AudioFocusRequest?)

/**
 * One mic open. Owns its pre-roll buffer, recorder, focus hold and reader; nothing here is
 * shared with a later capture, so a later capture can only open the mic after [reader] ends.
 */
private class Capture(
    val sessionId: Long,
    val buffer: PreRollAudioBuffer,
    val recorder: AudioRecord,
    val focus: FocusHold?,
) {
    lateinit var reader: Job
}

/** Engine identity: a change of size or thread count needs a reopen. */
private data class EngineKey(val size: Asr4AllSize, val threads: Int)

/**
 * Adaptive speech gate: a chunk is speech when it is well above the quietest of the last
 * [NOISE_WINDOW_CHUNKS]. Steady background noise therefore does not hold an utterance open, and a
 * soft speaker in a quiet room still counts. The window needs a quiet reference, which the pre-roll
 * buffer normally supplies.
 */
private class SpeechGate {
    private val window = FloatArray(NOISE_WINDOW_CHUNKS) { Float.MAX_VALUE }
    private var next = 0

    /** Records [rms] and returns whether it is speech. */
    fun isSpeech(rms: Float): Boolean {
        window[next] = rms
        next = (next + 1) % window.size
        return rms >= maxOf(SPEECH_RMS_MIN, window.min() * SPEECH_NOISE_MARGIN)
    }
}

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
 * engine has no endpoint signal, so an utterance ends after [ENDPOINT_SILENCE_CHUNKS] chunks without
 * speech (see [SpeechGate]) that follow decoded text. The final text is [Asr4AllEngine.finish].
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
    internal var releaseFencedForTest: (() -> Unit)? = null
    /** Runs on the caller just before a failed load joins its reader; lets a test cancel the caller at that point. */
    internal var beforeFailedLoadJoinForTest: (() -> Unit)? = null
    /** Runs on the caller just before a cancelled stop joins its reader; lets a test observe the reader still parked. */
    internal var beforeCancelledStopJoinForTest: (() -> Unit)? = null
    /** Runs on the capture job between its final supersede check and its emit; lets a test supersede inside that window. */
    internal var beforeFinalEmitForTest: (() -> Unit)? = null

    /** Missing weights and a refused Terms acceptance both read as unavailable, so callers fall back the same way. */
    suspend fun isAvailable(): Boolean = termsAccepted() && (availableForTest?.invoke()
        ?: withContext(Dispatchers.IO) { Asr4AllInstall.isDownloaded(context, selectedSize()) })

    /** The size to load now: the user's choice, subject to the tier policy. */
    private suspend fun selectedSize(): Asr4AllSize = Asr4AllInstall.effectiveSize(
        voiceInputPreferences.asr4allSize.first(),
        hardwareProfileDetector.profile.tier,
    )

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
            if (!isAvailable()) {
                return@withLock VoiceInputStartResult.Unavailable(
                    "Offline speech model is not downloaded. Download it in Settings → Voice.",
                )
            }
            if (!micGranted()) {
                return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
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
                    openCapture(sessionId)?.also {
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

            // The load runs on the controller scope, so a cancelled caller leaves the await at
            // once and ends the mic before the blocking native open returns. The load is still
            // awaited, non-cancellably, so the start lock is not released while it runs.
            val loading = scope.async(Dispatchers.IO) { ensureEngine() }
            val eng = try {
                loading.await()
            } catch (e: Throwable) {
                halt(capture)
                joinReader(capture)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                withContext(NonCancellable) {
                    loading.join()
                }
                throw e
            }
            if (eng == null) {
                halt(capture)
                beforeFailedLoadJoinForTest?.invoke()
                joinReader(capture)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                return@withLock VoiceInputStartResult.Unavailable(
                    "Offline speech model could not be loaded. Re-download it in Settings → Voice.",
                )
            }

            // A stop that lands while the model loads ends the capture without a final, matching
            // the Sherpa cold-load contract. Only a capture already attached to a ready engine is
            // finalized by an explicit stop; a supersede discards in every case.
            if (discard.get() || stop.get()) {
                halt(capture)
                beforeCancelledStopJoinForTest?.invoke()
                joinReader(capture)
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                return@withLock VoiceInputStartResult.Cancelled(sessionId)
            }
            activeJob = scope.launch {
                try {
                    eng.reset()
                    runCapture(capture.buffer, eng, mode, sessionId, stop, discard)
                } catch (e: Exception) {
                    Log.e(TAG, "asr4all capture failed", e)
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.AUDIO_LOOP)
                    if (!discard.get()) _events.tryEmit(VoiceInputEvent.Error(mode, e.message ?: "Voice input error", sessionId))
                } finally {
                    capture.buffer.close()
                    joinReader(capture)
                    _inputLevel.value = 0f
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = discard.get()))
                }
            }
            VoiceInputStartResult.Started(sessionId)
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
    private fun openCapture(sessionId: Long): Capture? {
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
            val capture = Capture(sessionId, PreRollAudioBuffer(PreRollAudioBuffer.PRE_ROLL_SAMPLES), recorder, focus)
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
        try {
            if (!c.buffer.isClosed) c.recorder.startRecording()
            while (!c.buffer.isClosed) {
                val n = c.recorder.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) c.buffer.append(buf, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pre-roll reader error", e)
        } finally {
            c.buffer.close()
            stopRecorder(c.recorder)
            releaseAudioFocus(c.focus)
            synchronized(captureLock) {
                if (activeCapture === c) {
                    activeCapture = null
                    captureActive.value = false
                    activeStop = null
                    activeDiscard = null
                }
            }
        }
    }

    /**
     * Ends the mic side of [c] now: closes its buffer and stops a recorder that is recording, so
     * a read blocked in the recorder returns. Idempotent. The reader releases the rest.
     */
    private fun halt(c: Capture) {
        c.buffer.close()
        try {
            if (c.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) c.recorder.stop()
        } catch (_: Exception) {
        }
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
     * Runs the capture loop over the pre-roll chunks, then live audio, until the buffer ends
     * (explicit stop drains what was already captured), the endpoint is reached, or the
     * no-speech timeout expires. A discard ends it at once with nothing emitted.
     */
    private fun runCapture(
        buffer: PreRollAudioBuffer,
        eng: Asr4AllEngine,
        mode: VoiceCaptureMode,
        sessionId: Long,
        stop: AtomicBoolean,
        discard: AtomicBoolean,
    ) {
        val preRoll = ArrayDeque(buffer.attach())
        var heard = false
        var textHeard = false // the engine has produced nonempty partial text
        var partial = ""
        var chunks = 0
        val gate = SpeechGate()
        var quietChunks = 0 // consecutive chunks that are not speech
        var bufferEnded = false // set only when the mic reader ends the buffer; the sole microphone failure (#2059)
        while (!discard.get() && chunks < MAX_UTTERANCE_CHUNKS) {
            val pcm = nextCaptureChunk(preRoll, buffer)
            if (pcm == null) {
                bufferEnded = true
                break
            }
            chunks++
            val n = pcm.size
            val floats = FloatArray(n)
            var sumSquares = 0.0
            for (i in 0 until n) {
                val f = pcm[i] / 32768f
                floats[i] = f
                sumSquares += f * f
            }
            val rms = sqrt(sumSquares / n).toFloat()
            _inputLevel.value = rms.coerceIn(0f, 1f)
            if (gate.isSpeech(rms)) {
                quietChunks = 0
                if (!heard) {
                    heard = true
                    _events.tryEmit(VoiceInputEvent.SpeechDetected(mode, sessionId))
                }
            } else {
                quietChunks++
            }

            // The engine returns its running text; emit only when it changes.
            val text = eng.acceptAudio(floats).trim()
            if (text.isNotEmpty()) textHeard = true
            if (text.isNotEmpty() && text != partial) {
                partial = text
                if (!discard.get()) _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, text, sessionId))
            }

            if (textHeard && quietChunks >= ENDPOINT_SILENCE_CHUNKS) break
            if (!textHeard && chunks >= NO_SPEECH_TIMEOUT_CHUNKS) break
        }
        _inputLevel.value = 0f
        // Superseded: no finish, no error, no transcript.
        if (discard.get()) return
        if (textHeard) _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, sessionId))
        val text = eng.finish().trim()
        // A release may arrive during the final decode; a superseded transcript is dropped.
        if (discard.get()) return
        beforeFinalEmitForTest?.invoke()
        if (text.isNotEmpty()) {
            JandalEvents.record("voice", "stt_result", "chars" to text.length)
            _events.tryEmit(VoiceInputEvent.Transcript(mode, text, sessionId))
        } else if (!stop.get()) {
            // An explicit stop with nothing decoded ends without an error, as before.
            when {
                // Only a real early end of the mic buffer is a microphone failure (#2059).
                bufferEnded -> {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "Microphone stopped. Try again.",
                            captureSessionId = sessionId,
                        ),
                    )
                }
                // Only the window's own timer exhausts the no-speech window. Silence is not a recognizer
                // failure: the category stops the Command/wake retry (#2051).
                !textHeard && chunks >= NO_SPEECH_TIMEOUT_CHUNKS -> {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "No speech heard. Try again.",
                            captureSessionId = sessionId,
                            category = NO_SPEECH_WINDOW_EXHAUSTED,
                        ),
                    )
                }
                // Text was heard, the utterance endpointed or hit the cap, and the final decode is empty. This is a
                // failed recognition, not a microphone failure. The export records EMPTY_FINAL; the Error carries no
                // category, so the normal single retry applies.
                else -> {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.EMPTY_FINAL)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode = mode,
                            message = "Didn't catch that. Try again.",
                            captureSessionId = sessionId,
                        ),
                    )
                }
            }
        }
    }

    /** Explicit finalize: ends the mic now; the captured audio is decoded and emitted. */
    override fun stopListening() {
        synchronized(captureLock) {
            stopCount++
            activeStop?.set(true)
            activeCapture?.let(::halt)
        }
    }
    internal fun stopListeningForSession(captureSessionId: Long) {
        synchronized(captureLock) {
            val capture = activeCapture?.takeIf { it.sessionId == captureSessionId } ?: return
            stopCount++
            activeStop?.set(true)
            halt(capture)
        }
    }

    internal fun cancelListeningForSession(captureSessionId: Long) {
        synchronized(captureLock) {
            val capture = activeCapture?.takeIf { it.sessionId == captureSessionId } ?: return
            stopCount++
            activeDiscard?.set(true)
            activeStop?.set(true)
            halt(capture)
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
        activeCapture?.let(::halt)
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
