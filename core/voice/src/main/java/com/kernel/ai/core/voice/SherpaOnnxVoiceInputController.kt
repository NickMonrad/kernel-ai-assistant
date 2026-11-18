package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.annotation.SuppressLint
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.math.sqrt
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.SttFailure

private const val MODEL_UNAVAILABLE_MESSAGE =
    "Sherpa-ONNX STT model not available — download it from Settings → Voice."

/**
 * VoiceInputController backed by Sherpa-ONNX recognizers accessed entirely via
 * Java reflection — zero compile-time dependency on `com.k2fsa.sherpa.onnx.*`.
 *
 * Supports four model families via [SherpaSttModelSpec]:
 * - Zipformer (existing): streaming via [OnlineRecognizer]
 * - Paraformer: streaming via [OnlineRecognizer]
 * - Whisper tiny.en: offline batch via [OfflineRecognizer]
 * - SenseVoice: offline batch via [OfflineRecognizer]
 *
 * The Sherpa-ONNX AAR is a runtime dependency in :app; :core:voice compiles without it.
 *
 * Threading model:
 * - [startListening] calls are serialised by [sessionMutex]. The model is checked first, then
 *   the stop check and the microphone open run together under [captureLock], which
 *   [stopListening] also takes. A stop before that point ends the start with no microphone,
 *   no audio focus and no `ListeningStarted`; a stop after it closes the open capture.
 * - Pre-roll: the microphone is read from open until a recognizer loop attaches, so speech
 *   during the recognizer load is kept (#1850). The window is capped at
 *   [PreRollAudioBuffer.PRE_ROLL_SAMPLES] (15 s).
 * - Recognizer creation is serialised by [recognizerMutex]; cached and reused
 *   unless the spec changes (recreates the recognizer).
 * - The audio capture loop runs on [Dispatchers.IO]. [_events] has an unbounded buffer so
 *   observers on Main never block the IO loop and no event is dropped. [inputLevel] is the
 *   only state; it is updated per chunk and reset to 0 when the loop ends.
 */
@Singleton
class SherpaOnnxVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceInputPreferences: VoiceInputPreferences,
) : VoiceInputController {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SherpaSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = (0.1 * SAMPLE_RATE).toInt() // 100 ms per chunk
        private const val LISTEN_TIMEOUT_MS = 15_000L
        private const val OFFLINE_SPEECH_RMS_THRESHOLD = 0.02f
        private const val OFFLINE_TRAILING_SILENCE_FRAMES = 25 // 2.5 s — tolerate natural speech pauses
        private const val PKG = "com.k2fsa.sherpa.onnx"
    }

    // ── Reflected recogniser state ─────────────────────────────────────────────

    /** The spec for which [recognizer] was built. Null when no recognizer exists. */
    @Volatile private var activeSpec: SherpaSttModelSpec? = null

    /** The underlying Sherpa recognizer (OnlineRecognizer or OfflineRecognizer). */
    @Volatile private var recognizer: Any? = null
    private val recognizerMutex = Mutex()

    /** Reflected method handles — populated once per recognizer init. */
    private val streamMethods = StreamMethods()
    private val onlineMethods = OnlineMethods()
    private val offlineMethods = OfflineMethods()

    /**
     * Single ownership boundary for the dedicated wake-verifier recognizers (#1440).
     *
     * The online fallback (Zipformer/Paraformer) and offline (Whisper) wake verifiers
     * are mutually exclusive: at most one is cached/resident at a time, and a
     * recognizer is never released while a verification using it is in progress.
     * All wake-verifier selection, initialization, decoding, result extraction and
     * stream release run under this mutex; the release* helpers must only be called
     * while it is held. The interactive STT recognizer and its [recognizerMutex]
     * are deliberately separate and unaffected.
     */
    private val wakeVerifierMutex = Mutex()
    @Volatile private var wakeRecognizer: Any? = null
    @Volatile private var wakeMethods: WakeRecognizerMethods? = null
    @Volatile private var wakeSpecEngine: VoiceInputEngine? = null
    @Volatile private var wakeSpecValid: Boolean = false

    /**
     * Test double for dedicated wake-verifier construction (#1440). When set,
     * [ensureWakeRecognizerBlocking]/[ensureWakeWhisperRecognizerBlocking] use the
     * provided recognizer+methods pairs instead of reflection-based construction, so
     * unit tests can drive the real cache-ownership lifecycle without the Sherpa AAR
     * on the test classpath. Production callers never set this.
     */
    @Volatile internal var wakeVerifierTestBuilder: WakeVerifierTestBuilder? = null

    /**
     * Dedicated offline recognizer for wake-word verification (Whisper tiny.en).
     *
     * #1439: Whisper tiny.en is the only catalogue model that recognises the fixed
     * natural wake phrase on the detector-equivalent PCM window (`hi, jandal`);
     * Zipformer/Paraformer/Vosk/SenseVoice all reject it (deterministic reproduction
     * with the exact on-device files). The offline wake recognizer is a separate
     * instance so verification can run concurrently with interactive capture.
     */
    @Volatile private var wakeWhisperRecognizer: Any? = null
    @Volatile private var wakeWhisperMethods: WakeWhisperMethods? = null
    @Volatile private var wakeWhisperEngine: VoiceInputEngine? = null
    @Volatile private var wakeWhisperValid: Boolean = false

    /** #1440 test visibility: whether each dedicated wake verifier is currently cached. */
    internal val wakeOnlineVerifierCached: Boolean get() = wakeRecognizer != null
    internal val wakeWhisperVerifierCached: Boolean get() = wakeWhisperRecognizer != null

    /** #1440 test visibility: the interactive STT recognizer must stay untouched. */
    internal val interactiveRecognizerActive: Boolean get() = recognizer != null

    /** Holds reflected methods shared by both online and offline recognizers. */
    class StreamMethods {
        @Volatile var createStream: java.lang.reflect.Method? = null
        @Volatile var acceptWaveform: java.lang.reflect.Method? = null
        @Volatile var inputFinished: java.lang.reflect.Method? = null
        @Volatile var getText: java.lang.reflect.Method? = null
        @Volatile var streamRelease: java.lang.reflect.Method? = null
    }

    /** Reflected methods dedicated to wake-word verification. */
    class WakeRecognizerMethods(
        val createStream: java.lang.reflect.Method,
        val acceptWaveform: java.lang.reflect.Method,
        val inputFinished: java.lang.reflect.Method,
        val isReady: java.lang.reflect.Method,
        val decode: java.lang.reflect.Method,
        val streamRelease: java.lang.reflect.Method,
        val getResult: java.lang.reflect.Method,
    )

    /** Reflected methods dedicated to offline (Whisper) wake-word verification. */
    class WakeWhisperMethods(
        val createStream: java.lang.reflect.Method,
        val acceptWaveform: java.lang.reflect.Method,
        val decode: java.lang.reflect.Method,
        val getResult: java.lang.reflect.Method,
        val streamRelease: java.lang.reflect.Method,
    )

    /**
     * Test seam for dedicated wake-verifier construction (#1440). Implementations
     * return recognizer+reflected-methods pairs without touching the Sherpa AAR.
     */
    interface WakeVerifierTestBuilder {
        fun buildOnline(spec: SherpaSttModelSpec): Pair<Any, WakeRecognizerMethods>?
        fun buildWhisper(spec: SherpaSttModelSpec): Pair<Any, WakeWhisperMethods>?
    }

    /** Methods specific to OnlineRecognizer. */
    class OnlineMethods {
        @Volatile var isReady: java.lang.reflect.Method? = null
        @Volatile var decode: java.lang.reflect.Method? = null
        @Volatile var isEndpoint: java.lang.reflect.Method? = null
        @Volatile var getResult: java.lang.reflect.Method? = null
        @Volatile var reset: java.lang.reflect.Method? = null
    }

    /** Methods specific to OfflineRecognizer. */
    class OfflineMethods {
        @Volatile var decode: java.lang.reflect.Method? = null
        @Volatile var getResult: java.lang.reflect.Method? = null
    }

    // ── Session state ──────────────────────────────────────────────────────────

    private val sessionMutex = Mutex()
    private val recordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeJob: Job? = null

    /** Guards opening the microphone against [stopListening]; see the class threading notes. */
    private val captureLock = Any()

    /** Incremented by every [stopListening]; a start abandons itself if it changed meanwhile. */
    private var stopCount = 0L
    /** The microphone capture opened by [startListening], until its recognizer loop ends. */
    @Volatile private var currentCapture: PreRollAudioBuffer? = null
    @Volatile private var activeSessionId: Long? = null
    /** The pre-roll reader of the most recent capture; a new start waits for it to release the mic. */
    @Volatile private var lastReaderJob: Job? = null
    private data class CaptureLifecycle(
        val mode: VoiceCaptureMode,
        val sessionId: Long,
        val capture: PreRollAudioBuffer,
        val readerJob: Job,
        var recordingJob: Job? = null,
        var terminalEmitted: Boolean = false,
        var cancelled: Boolean = false,
    )
    @Volatile private var activeLifecycle: CaptureLifecycle? = null

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    // ── Audio focus ────────────────────────────────────────────────────────────

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    @Volatile private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // ── Events ─────────────────────────────────────────────────────────────────

    private val _events = voiceEventFlow()
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    // ── VoiceInputController ───────────────────────────────────────────────────

    /**
     * Starts a capture. The microphone opens once the model is known to be present, so speech
     * during the recognizer load is kept in a [PreRollAudioBuffer] (#1850). A stop before the
     * microphone opens, including while waiting for the previous session, ends the start without
     * opening the microphone, playing the cue or emitting `ListeningStarted`; the result is
     * `Started` with a `ListeningStopped` event. A stop after the microphone opened ends the
     * session through the normal path. Cancelling the caller while the recognizer loads throws
     * [kotlinx.coroutines.CancellationException] and releases the microphone and audio focus.
     */
    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        startListening(mode, isWanted = { true })

    /**
     * [isWanted] is checked together with the stop count under [captureLock], right before the
     * microphone opens and again after the recognizer loads. [VoiceInputController] callers that
     * supersede this start (Selectable's generation) return false, so no mic opens for it.
     */
    internal suspend fun startListening(
        mode: VoiceCaptureMode,
        isWanted: () -> Boolean,
        onSessionAdmitted: (Long) -> Unit = {},
    ): VoiceInputStartResult {
        val stopsAtCall = synchronized(captureLock) { stopCount }
        return sessionMutex.withLock {
            val previousLifecycle = activeLifecycle
            val previousJob = activeJob
            previousLifecycle?.let { previous ->
                synchronized(captureLock) {
                    previous.cancelled = true
                    isRecording = false
                    previous.capture.close()
                }
            }
            previousJob?.cancel()
            previousJob?.join()
            previousLifecycle?.let { finishCapture(it) }
            lastReaderJob?.join()
            synchronized(captureLock) {
                if (activeJob === previousJob) activeJob = null
                if (activeLifecycle === previousLifecycle) activeLifecycle = null
            }
            lastReaderJob = null
            val captureSessionId = VoiceCaptureSessionIds.allocate()
            val spec = resolveSpec()
            if (!isSpecAvailable(spec)) {
                JandalEvents.record("voice", "stt_error", "category" to SttFailure.MODEL_UNAVAILABLE)
                return@withLock VoiceInputStartResult.Unavailable(MODEL_UNAVAILABLE_MESSAGE)
            }
            synchronized(captureLock) { activeSessionId = captureSessionId }
            onSessionAdmitted(captureSessionId)

            beforeMicOpenForTest?.invoke()
            var lifecycle: CaptureLifecycle? = null
            var stoppedEarly = false
            val capture = synchronized(captureLock) {
                if (stopCount != stopsAtCall || !isWanted()) {
                    stoppedEarly = true
                    null
                } else {
                    openPreRollCapture()?.also { opened ->
                        val reader = checkNotNull(lastReaderJob)
                        lifecycle = CaptureLifecycle(mode, captureSessionId, opened, reader)
                        activeLifecycle = lifecycle
                        currentCapture = opened
                        _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, captureSessionId))
                    }
                }
            }
            if (stoppedEarly) return@withLock endStoppedBeforeMic(mode, captureSessionId)
            if (capture == null) {
                clearActiveSession(captureSessionId)
                return@withLock VoiceInputStartResult.Unavailable(
                    "Microphone unavailable — check RECORD_AUDIO permission.",
                )
            }
            val captureLifecycle = checkNotNull(lifecycle)

            val rec = try {
                ensureRecognizer(spec)
            } catch (e: Throwable) {
                captureLifecycle.cancelled = e is CancellationException
                finishCapture(captureLifecycle)
                throw e
            }
            if (rec == null) {
                finishCapture(captureLifecycle)
                JandalEvents.record("voice", "stt_error", "category" to SttFailure.MODEL_UNAVAILABLE)
                return@withLock VoiceInputStartResult.Unavailable(MODEL_UNAVAILABLE_MESSAGE)
            }

            val callerContext = currentCoroutineContext()
            var callerCancellation: CancellationException? = null
            var stoppedDuringLoad = false
            val started = synchronized(captureLock) {
                try {
                    callerContext.ensureActive()
                } catch (e: CancellationException) {
                    callerCancellation = e
                }
                if (callerCancellation != null) {
                    null
                } else if (stopCount != stopsAtCall || !isWanted()) {
                    stoppedDuringLoad = true
                    captureLifecycle.cancelled = true
                    Log.i(TAG, "Sherpa STT start abandoned: stopped while the recognizer was loading (sessionId=$captureSessionId)")
                    null
                } else {
                    JandalEvents.record("voice", "stt_ready", "mode" to mode, "zipformer" to (spec == SherpaSttModelSpec.ZIPFORMER))
                    isRecording = true
                    val job = recordingScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            when (spec.recognizerKind) {
                                SherpaSttModelSpec.RecognizerKind.Online ->
                                    onlineAudioLoop(capture, mode, captureSessionId, rec, spec)
                                SherpaSttModelSpec.RecognizerKind.Offline ->
                                    offlineAudioLoop(capture, mode, captureSessionId, rec, spec)
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                JandalEvents.record("voice", "stt_error", "category" to SttFailure.AUDIO_LOOP)
                                Log.e(TAG, "Audio loop error", e)
                                _events.tryEmit(
                                    VoiceInputEvent.Error(
                                        mode,
                                        e.message ?: "Voice input error",
                                        captureSessionId,
                                    ),
                                )
                            }
                        } finally {
                            finishCapture(captureLifecycle, waitForRecordingJob = false)
                        }
                    }
                    activeJob = job
                    captureLifecycle.recordingJob = job
                    job.invokeOnCompletion {
                        recordingScope.launch { finishCapture(captureLifecycle) }
                    }
                    beforeRecordingJobStartForTest?.invoke()
                    if (!job.start()) {
                        captureLifecycle.cancelled = true
                        stoppedDuringLoad = true
                        null
                    } else {
                        VoiceInputStartResult.Started(captureSessionId)
                    }
                }
            }
            if (stoppedDuringLoad || callerCancellation != null) {
                captureLifecycle.cancelled = true
                finishCapture(captureLifecycle)
                callerCancellation?.let { throw it }
                return@withLock VoiceInputStartResult.Cancelled(captureSessionId)
            }
            checkNotNull(started)
        }
    }

    private fun clearActiveSession(captureSessionId: Long) {
        synchronized(captureLock) {
            if (activeSessionId == captureSessionId) activeSessionId = null
        }
    }

    private suspend fun finishCapture(
        lifecycle: CaptureLifecycle,
        waitForRecordingJob: Boolean = true,
    ) {
        withContext(NonCancellable) {
            lifecycle.capture.close()
            lifecycle.readerJob.join()
            if (waitForRecordingJob) {
                val recordingJob = lifecycle.recordingJob
                if (recordingJob != null && recordingJob !== currentCoroutineContext()[Job]) {
                    recordingJob.join()
                }
            }
            val shouldEmit = synchronized(captureLock) {
                if (lifecycle.terminalEmitted) {
                    false
                } else {
                    lifecycle.terminalEmitted = true
                    if (currentCapture === lifecycle.capture) currentCapture = null
                    if (activeSessionId == lifecycle.sessionId) activeSessionId = null
                    if (activeLifecycle === lifecycle) {
                        activeLifecycle = null
                        if (activeJob === lifecycle.recordingJob) activeJob = null
                    }
                    isRecording = false
                    _inputLevel.value = 0f
                    true
                }
            }
            if (shouldEmit) {
                _events.tryEmit(
                    VoiceInputEvent.ListeningStopped(
                        lifecycle.mode,
                        lifecycle.sessionId,
                        cancelled = lifecycle.cancelled,
                    ),
                )
            }
        }
    }

    /** A stop arrived before the microphone opened: no mic, cue or haptic (#1850). */
    private fun endStoppedBeforeMic(mode: VoiceCaptureMode, captureSessionId: Long): VoiceInputStartResult {
        clearActiveSession(captureSessionId)
        Log.i(TAG, "Sherpa STT start abandoned: stopped before the microphone opened (sessionId=$captureSessionId)")
        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, captureSessionId, cancelled = true))
        return VoiceInputStartResult.Cancelled(captureSessionId)
    }

    /** Test seam: runs after the model check and before the stop check that guards the mic open. */
    internal var beforeMicOpenForTest: (() -> Unit)? = null
    /** Test seam: stops after readiness admission but before the IO job can begin its body. */
    internal var beforeRecordingJobStartForTest: (() -> Unit)? = null

    /** Test seam: supplies the microphone recorder in place of the platform [AudioRecord]. */
    internal var audioRecordForTest: (() -> AudioRecord?)? = null

    /**
     * #1850: loads the selected Sherpa recognizer before the first tap. Holds no mic; does nothing
     * when a non-Sherpa engine (Android native) is selected, so no Sherpa model is loaded for it.
     */
    override suspend fun prewarm() {
        val spec = SherpaSttModelSpec.forEngine(voiceInputPreferences.selectedEngine.first()) ?: return
        prewarmSpec(spec)
    }

    /**
     * Loads [spec] ahead of a start (#1850). Selectable uses this for the Zipformer route that
     * Auto picks, which [prewarm] cannot resolve from the engine setting alone. Holds no mic.
     */
    internal suspend fun prewarmSpec(spec: SherpaSttModelSpec) {
        try {
            ensureRecognizer(spec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sherpa recognizer pre-warm failed; first tap will retry", e)
        }
    }

    override fun stopListening() {
        requestStop(null)
    }

    internal fun stopListeningForSession(captureSessionId: Long) {
        requestStop(captureSessionId)
    }

    internal fun cancelListeningForSession(captureSessionId: Long) {
        requestStop(captureSessionId)
    }

    private fun requestStop(captureSessionId: Long?) {
        val (lifecycle, job) = synchronized(captureLock) {
            if (captureSessionId != null && activeSessionId != captureSessionId) return
            stopCount++
            isRecording = false
            val currentLifecycle = activeLifecycle?.also {
                it.cancelled = true
                it.capture.close()
            }
            val currentJob = currentLifecycle?.recordingJob
                ?: activeJob.takeIf { currentLifecycle == null && activeSessionId == null }
            if (currentLifecycle == null) currentCapture?.close()
            currentLifecycle to currentJob
        }
        job?.cancel()
        lifecycle?.let { capture ->
            recordingScope.launch { finishCapture(capture) }
        }
    }

    internal fun hasCaptureOrStart(): Boolean = activeSessionId != null

    /** Ends a capture that never reached a recognizer loop. The reader releases the mic and focus. */
    private fun abandonCapture(capture: PreRollAudioBuffer) {
        capture.close()
        synchronized(captureLock) {
            if (currentCapture === capture) currentCapture = null
        }
    }

    /**
     * Opens the microphone, requests audio focus, and starts a reader that keeps the newest
     * [PreRollAudioBuffer.PRE_ROLL_SAMPLES] samples until a recognizer loop attaches (#1850).
     * Returns null, with focus released, when the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission")
    private fun openPreRollCapture(): PreRollAudioBuffer? {
        requestAudioFocus()
        val ar = (audioRecordForTest?.invoke() ?: createAudioRecord()) ?: run {
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
            releaseAudioFocus()
            return null
        }
        val buffer = PreRollAudioBuffer(PreRollAudioBuffer.PRE_ROLL_SAMPLES)
        lastReaderJob = recordingScope.launch {
            val buf = ShortArray(CHUNK_SAMPLES)
            try {
                ar.startRecording()
                while (isActive && !buffer.isClosed) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) buffer.append(buf, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pre-roll reader error", e)
            } finally {
                buffer.close()
                stopAudioRecord(ar)
                releaseAudioFocus()
            }
        }
        return buffer
    }

    // ── Online audio loop (Zipformer / Paraformer) ─────────────────────────────

    @Volatile private var isRecording = false

    /**
     * Blocking audio capture loop for online (streaming) recognizers.
     * Feeds 100 ms PCM chunks to Sherpa incrementally, decodes on every chunk,
     * and emits partial results.  On endpoint or timeout, flushes and emits final text.
     */
    private fun onlineAudioLoop(
        buffer: PreRollAudioBuffer,
        mode: VoiceCaptureMode,
        captureSessionId: Long,
        rec: Any,
        spec: SherpaSttModelSpec,
    ) {
        // Hotwords (#1852 investigation): NOT enabled. The source model
        // Zengwei/icefall-asr-librispeech-pruned-transducer-stateless7-streaming-2022-12-29 ships
        // data/lang_bpe_500/bpe.model with the same tokens.txt, and sherpa's
        // scripts/export_bpe_vocab.py can build the bpe vocab. Enabling hotwords still needs that
        // BPE asset shipped, a non-greedy decoder (modified_beam_search), and modelingUnit set in the
        // recognizer config. Until then, pass "" here: with modelingUnit empty, any non-empty hotwords
        // string passed to createStream exits the process.
        val stream = streamMethods.createStream!!.invoke(rec, "") // createStream(hotwords: String)

        val preRoll = ArrayDeque(buffer.attach())
        buffer.trimmedSamples.takeIf { it > 0L }?.let {
            Log.w(TAG, "Pre-roll trimmed $it samples: recognizer load outlasted the ${PreRollAudioBuffer.PRE_ROLL_SAMPLES}-sample window")
        }
        val floatBuf = FloatArray(CHUNK_SAMPLES)
        var lastPartial = ""
        val started = System.currentTimeMillis()
        var exitedFromLoop = false

        try {
            while (isRecording) {
                val chunk = nextCaptureChunk(preRoll, buffer) ?: break
                val exactFloat = if (chunk.size == floatBuf.size) floatBuf else FloatArray(chunk.size)
                for (i in chunk.indices) exactFloat[i] = chunk[i] / 32768f
                _inputLevel.value = levelFromRms(chunkRms(exactFloat))
                streamMethods.acceptWaveform!!.invoke(stream, exactFloat, SAMPLE_RATE)

                // Drain — Sherpa may buffer multiple decodable chunks per feed.
                while (onlineMethods.isReady!!.invoke(rec, stream) as Boolean) {
                    onlineMethods.decode!!.invoke(rec, stream)
                }

                // ── Endpoint detection ─────────────────────────────────────
                if (onlineMethods.isEndpoint!!.invoke(rec, stream) as Boolean) {
                    val text = signalEndAndDrainOnline(rec, stream)
                    if (text.isNotEmpty()) {
                        _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, captureSessionId))
                        JandalEvents.record("voice", "stt_result", "chars" to text.length)
                        _events.tryEmit(VoiceInputEvent.Transcript(mode, text, captureSessionId))
                        lastPartial = ""
                    }
                    onlineMethods.reset!!.invoke(rec, stream)
                    if (text.isNotEmpty()) {
                        exitedFromLoop = true
                        isRecording = false
                        break
                    }
                    continue
                }

                // ── Timeout ────────────────────────────────────────────────
                if (System.currentTimeMillis() - started > LISTEN_TIMEOUT_MS) {
                    _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, captureSessionId))
                    val text = signalEndAndDrainOnline(rec, stream)
                    if (text.isNotEmpty()) {
                        JandalEvents.record("voice", "stt_result", "chars" to text.length)
                        _events.tryEmit(VoiceInputEvent.Transcript(mode, text, captureSessionId))
                    } else {
                        JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                        _events.tryEmit(
                            VoiceInputEvent.Error(
                                mode,
                                "I didn't catch anything — please try again.",
                                captureSessionId,
                            ),
                        )
                    }
                    exitedFromLoop = true
                    isRecording = false
                    break
                }

                // ── Partial transcript ─────────────────────────────────────
                val partial = resultTextOnline(rec, stream)
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, partial, captureSessionId))
                }
            }

            if (!exitedFromLoop) {
                _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, captureSessionId))
                val text = signalEndAndDrainOnline(rec, stream)
                if (text.isNotEmpty()) {
                    JandalEvents.record("voice", "stt_result", "chars" to text.length)
                    _events.tryEmit(VoiceInputEvent.Transcript(mode, text, captureSessionId))
                } else {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                }
            }
        } finally {
            streamMethods.streamRelease!!.invoke(stream)
        }
    }

    private fun signalEndAndDrainOnline(rec: Any, stream: Any): String {
        streamMethods.inputFinished!!.invoke(stream)
        while (onlineMethods.isReady!!.invoke(rec, stream) as Boolean) {
            onlineMethods.decode!!.invoke(rec, stream)
        }
        return resultTextOnline(rec, stream)
    }

    private fun resultTextOnline(rec: Any, stream: Any): String {
        val result = onlineMethods.getResult!!.invoke(rec, stream)!!
        val getText = streamMethods.getText
            ?: result.javaClass.getDeclaredMethod("getText")
                .also { it.isAccessible = true; streamMethods.getText = it }
        return TranscriptNormaliser.normalise(
            (getText.invoke(result) as String).trim().trimEnd('?', '.', '!', ',', ':', ';')
                .lowercase(java.util.Locale.ROOT)
        )
    }

    // ── Offline audio loop (Whisper / SenseVoice) ──────────────────────────────

    /**
     * Blocking audio capture loop for offline (batch) recognizers.
     * Records until stop or timeout, then decodes the full buffer once and emits final text.
     * Does not emit partial results.
     */
    private fun offlineAudioLoop(
        buffer: PreRollAudioBuffer,
        mode: VoiceCaptureMode,
        captureSessionId: Long,
        rec: Any,
        spec: SherpaSttModelSpec,
    ) {
        val stream = streamMethods.createStream!!.invoke(rec)

        val preRoll = ArrayDeque(buffer.attach())
        buffer.trimmedSamples.takeIf { it > 0L }?.let {
            Log.w(TAG, "Pre-roll trimmed $it samples: recognizer load outlasted the ${PreRollAudioBuffer.PRE_ROLL_SAMPLES}-sample window")
        }
        var recorded = FloatArray(SAMPLE_RATE * 2)
        var recordedCount = 0
        val started = System.currentTimeMillis()
        var speechDetected = false
        var trailingSilenceFrames = 0

        try {
            while (isRecording) {
                val chunk = nextCaptureChunk(preRoll, buffer) ?: break
                if (recordedCount + chunk.size > recorded.size) {
                    recorded = recorded.copyOf(maxOf(recorded.size * 2, recordedCount + chunk.size))
                }
                var sumSq = 0.0
                for (i in chunk.indices) {
                    val v = chunk[i] / 32768f
                    recorded[recordedCount + i] = v
                    sumSq += (v * v).toDouble()
                }
                recordedCount += chunk.size
                val rms = if (chunk.isEmpty()) 0f else sqrt((sumSq / chunk.size).toFloat())
                _inputLevel.value = levelFromRms(rms)
                if (rms >= OFFLINE_SPEECH_RMS_THRESHOLD) {
                    speechDetected = true
                    trailingSilenceFrames = 0
                } else if (speechDetected) {
                    trailingSilenceFrames++
                    if (trailingSilenceFrames >= OFFLINE_TRAILING_SILENCE_FRAMES) break
                }

                if (System.currentTimeMillis() - started > LISTEN_TIMEOUT_MS) break
            }

            // Decode the recorded audio once. The buffer grows geometrically, so this is the only copy.
            _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, captureSessionId))
            if (recordedCount > 0) {
                val fullPcm = recorded.copyOf(recordedCount)

                streamMethods.acceptWaveform!!.invoke(stream, fullPcm, SAMPLE_RATE)
                streamMethods.inputFinished?.invoke(stream)
                offlineMethods.decode!!.invoke(rec, stream)

                val text = resultTextOffline(rec, stream)
                if (text.isNotEmpty()) {
                    JandalEvents.record("voice", "stt_result", "chars" to text.length)
                    _events.tryEmit(VoiceInputEvent.Transcript(mode, text, captureSessionId))
                } else {
                    JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                    _events.tryEmit(
                        VoiceInputEvent.Error(
                            mode,
                            "I didn't catch anything — please try again.",
                            captureSessionId,
                        ),
                    )
                }
            } else {
                JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
                _events.tryEmit(
                    VoiceInputEvent.Error(
                        mode,
                        "I didn't catch anything — please try again.",
                        captureSessionId,
                    ),
                )
            }
        } finally {
            streamMethods.streamRelease!!.invoke(stream)
        }
    }

    private fun resultTextOffline(rec: Any, stream: Any): String {
        val result = offlineMethods.getResult!!.invoke(rec, stream)!!
        val getText = streamMethods.getText
            ?: result.javaClass.getDeclaredMethod("getText")
                .also { it.isAccessible = true; streamMethods.getText = it }
        return TranscriptNormaliser.normalise(
            (getText.invoke(result) as String).trim().trimEnd('?', '.', '!', ',', ':', ';')
                .lowercase(java.util.Locale.ROOT)
        )
    }

    private fun chunkRms(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (v in chunk) sum += (v * v).toDouble()
        return kotlin.math.sqrt(sum / chunk.size).toFloat()
    }

    /** Maps raw chunk RMS to 0..1 for the level ring: -50 dBFS is 0, -10 dBFS is 1 (native is scaled alike). */
    private fun levelFromRms(rms: Float): Float {
        val db = 20f * kotlin.math.log10(kotlin.math.max(rms, 1e-6f))
        return ((db + 50f) / 40f).coerceIn(0f, 1f)
    }

    override suspend fun transcribeBlocking(pcm: ShortArray): String? {
        if (pcm.isEmpty()) return null
        // #1439: prefer the Whisper tiny.en verifier — the only catalogue model that
        // recognises the fixed natural wake phrase on the detector-equivalent window.
        // Falls back to the existing online verifier when the Whisper files are absent.
        // #1440: the whole verification — selection, initialization, decoding, result
        // extraction and stream release — runs under [wakeVerifierMutex], so the
        // online fallback and Whisper verifiers are mutually exclusive and a
        // recognizer is never released while its decode is still in progress.
        return wakeVerifierMutex.withLock {
            val spec = resolveWakeVerifierSpec()
            if (spec == SherpaSttModelSpec.WHISPER) {
                val (rec, methods) = ensureWakeWhisperRecognizerBlocking(spec) ?: return@withLock null
                val stream = methods.createStream.invoke(rec)
                try {
                    val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                    methods.acceptWaveform.invoke(stream, floats, SAMPLE_RATE)
                    methods.decode.invoke(rec, stream)
                    resultTextFromWakeWhisper(rec, stream, methods)
                } catch (e: Exception) {
                    Log.e(TAG, "transcribeBlocking (whisper verifier) failed", e)
                    null
                } finally {
                    runCatching { methods.streamRelease.invoke(stream) }
                }
            } else {
                val (rec, methods) = ensureWakeRecognizerBlocking(spec) ?: return@withLock null
                val stream = methods.createStream.invoke(rec, "")
                try {
                    val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                    methods.acceptWaveform.invoke(stream, floats, SAMPLE_RATE)
                    methods.inputFinished.invoke(stream)
                    var iters = 0

                    while (methods.isReady.invoke(rec, stream) as Boolean) {
                        methods.decode.invoke(rec, stream)
                        if (++iters > 500) break
                    }
                    resultTextFromWakeMethods(rec, stream, methods)
                } catch (e: Exception) {
                    Log.e(TAG, "transcribeBlocking failed", e)
                    null
                } finally {
                    runCatching { methods.streamRelease.invoke(stream) }
                }
            }
        }
    }

    /**
     * Resolves the model used for wake-window verification (#1439):
     *
     * 1. Whisper tiny.en when its files are present — the only catalogue model that
     *    recognises the fixed natural wake phrase on the detector-equivalent window;
     * 2. otherwise the selected online Sherpa spec (Zipformer/Paraformer) when online;
     * 3. otherwise the Zipformer default (existing fallback).
     *
     * The interactive STT engine selection is never modified.
     */
    internal suspend fun resolveWakeVerifierSpec(): SherpaSttModelSpec {
        if (isSpecAvailable(SherpaSttModelSpec.WHISPER)) return SherpaSttModelSpec.WHISPER
        val userSpec = resolveSpec()
        return if (userSpec.recognizerKind == SherpaSttModelSpec.RecognizerKind.Online) userSpec
               else SherpaSttModelSpec.WAKE_VERIFICATION_DEFAULT
    }

    /**
     * Ensures the dedicated online wake-verification recognizer (Zipformer/Paraformer
     * fallback) exists. The caller must hold [wakeVerifierMutex]: the Whisper wake
     * verifier is evicted before the online verifier becomes resident, so at most one
     * dedicated wake verifier is cached at a time (#1440). The interactive STT
     * recognizer and its mutex are unaffected.
     */
    private suspend fun ensureWakeRecognizerBlocking(
        spec: SherpaSttModelSpec,
    ): Pair<Any, WakeRecognizerMethods>? {
        if (wakeRecognizer != null && wakeSpecValid && wakeSpecEngine == spec.engine && wakeMethods != null) {
            return wakeRecognizer!! to wakeMethods!!
        }
        // Mutual exclusion: evict the Whisper wake verifier, then any superseded
        // online recognizer, before the new one becomes resident.
        releaseWakeWhisperRecognizer()
        releaseWakeRecognizer()
        if (!isSpecAvailable(spec)) {
            Log.w(TAG, "Wake-word STT model files missing for ${spec.engine}")
            return null
        }
        return try {
            val testBuilt = wakeVerifierTestBuilder?.buildOnline(spec)
            val instance: Any
            val methods: WakeRecognizerMethods
            if (testBuilt != null) {
                instance = testBuilt.first
                methods = testBuilt.second
            } else {
                instance = initWakeOnlineRecognizer(spec)
                methods = buildWakeMethods(instance)
            }
            wakeRecognizer = instance
            wakeMethods = methods
            wakeSpecEngine = spec.engine
            wakeSpecValid = true
            instance to methods
        } catch (e: Exception) {
            Log.e(TAG, "Wake recognizer init failed", e)
            wakeRecognizer = null
            wakeMethods = null
            wakeSpecEngine = null
            wakeSpecValid = false
            null
        }
    }
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 2),
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            Log.e(TAG, "AudioRecord init failed")
            return null
        }
        return ar
    }

    private fun stopAudioRecord(ar: AudioRecord) {
        try { if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar.stop() } catch (_: Exception) {}
        try { ar.release() } catch (_: Exception) {}
    }

    // ── Audio focus ────────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus({ }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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

    // ── STT model file paths ─────────────────────────────────────────────────

    private fun sttModelsDir(): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    private fun sttFile(fileName: String): File = File(sttModelsDir(), fileName)

    // ── Spec resolution ────────────────────────────────────────────────────────

    /**
     * Resolves the current Sherpa STT spec from the selected voice input engine.
     * If the selected engine is not a Sherpa family, returns the Zipformer spec
     * (so availability checks and wake-word fallback work).
     */
    private fun resolveSpec(): SherpaSttModelSpec {
        val engine = runBlockingOrNull { voiceInputPreferences.selectedEngine.first() }
            ?: VoiceInputEngine.SherpaZipformer
        return SherpaSttModelSpec.forEngine(engine) ?: SherpaSttModelSpec.WAKE_VERIFICATION_DEFAULT
    }

    // ── Availability ───────────────────────────────────────────────────────────

    /**
     * Returns true when all model files for the currently selected Sherpa spec exist.
     * Safe to call without triggering reflection.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val spec = resolveSpec()
        spec.requiredFileNames.all { name ->
            sttFile(name).let { it.exists() && it.length() > 0 }
        }
    }

    /**
     * Returns true when all model files for [spec] exist.
     */
    internal suspend fun isSpecAvailable(spec: SherpaSttModelSpec): Boolean = withContext(Dispatchers.IO) {
        spec.requiredFileNames.all { name ->
            sttFile(name).let { it.exists() && it.length() > 0 }
        }
    }

    // ── Lazy recogniser init ───────────────────────────────────────────────────

    private fun <T> runBlockingOrNull(block: suspend () -> T): T? {
        return try {
            kotlinx.coroutines.runBlocking { block() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureRecognizer(spec: SherpaSttModelSpec): Any? {
        if (recognizer != null && activeSpec?.engine == spec.engine) return recognizer
        return recognizerMutex.withLock {
            // Re-check after acquiring lock.
            if (recognizer != null && activeSpec?.engine == spec.engine) return@withLock recognizer

            // Release previous recognizer if spec changed.
            releaseRecognizer()

            if (!isSpecAvailable(spec)) {
                Log.w(TAG, "STT model files missing for ${spec.engine}")
                return@withLock null
            }

            try {
                // The model load blocks for about a second. Off the caller's thread, so a Main
                // caller stays responsive and a stop tapped during the load is handled (#1853).
                // A cancelled caller still caches the finished recognizer for the next start.
                withContext(Dispatchers.IO) { initRecognizer(spec) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Recognizer init failed for ${spec.engine}", e)
                null
            }
        }
    }

    private fun releaseRecognizer() {
        if (recognizer != null) {
            try {
                recognizer!!.javaClass.getDeclaredMethod("release").invoke(recognizer)
            } catch (_: Exception) {}
            recognizer = null
            activeSpec = null
        }
        // Clear all cached methods.
        streamMethods.createStream = null
        streamMethods.acceptWaveform = null
        streamMethods.inputFinished = null
        streamMethods.getText = null
        streamMethods.streamRelease = null
        onlineMethods.isReady = null
        onlineMethods.decode = null
        onlineMethods.isEndpoint = null
        onlineMethods.getResult = null
        onlineMethods.reset = null
        offlineMethods.decode = null
        offlineMethods.getResult = null
    }

    /**
     * Ensures the dedicated offline (Whisper) wake-verification recognizer exists.
     * The caller must hold [wakeVerifierMutex]: the online fallback wake verifier is
     * evicted before Whisper becomes resident, so at most one dedicated wake verifier
     * is cached at a time (#1440). The interactive STT recognizer and its mutex are
     * unaffected.
     */
    private suspend fun ensureWakeWhisperRecognizerBlocking(
        spec: SherpaSttModelSpec,
    ): Pair<Any, WakeWhisperMethods>? {
        if (wakeWhisperRecognizer != null && wakeWhisperValid && wakeWhisperEngine == spec.engine && wakeWhisperMethods != null) {
            return wakeWhisperRecognizer!! to wakeWhisperMethods!!
        }
        // Mutual exclusion: evict the online wake verifier, then any superseded
        // Whisper recognizer, before the new one becomes resident.
        releaseWakeRecognizer()
        releaseWakeWhisperRecognizer()
        if (!isSpecAvailable(spec)) {
            Log.w(TAG, "Wake-word Whisper verifier files missing for ${spec.engine}")
            return null
        }
        return try {
            val testBuilt = wakeVerifierTestBuilder?.buildWhisper(spec)
            val instance: Any
            val methods: WakeWhisperMethods
            if (testBuilt != null) {
                instance = testBuilt.first
                methods = testBuilt.second
            } else {
                instance = initWakeWhisperRecognizer(spec)
                methods = buildWakeWhisperMethods(instance)
            }
            wakeWhisperRecognizer = instance
            wakeWhisperMethods = methods
            wakeWhisperEngine = spec.engine
            wakeWhisperValid = true
            instance to methods
        } catch (e: Exception) {
            Log.e(TAG, "Wake Whisper verifier init failed", e)
            wakeWhisperRecognizer = null
            wakeWhisperMethods = null
            wakeWhisperEngine = null
            wakeWhisperValid = false
            null
        }
    }

    /**
     * Releases the dedicated offline (Whisper) wake-verification recognizer and
     * clears its cached method handles. Must only be called while [wakeVerifierMutex]
     * is held so a recognizer is never released mid-verification (#1440).
     */
    private fun releaseWakeWhisperRecognizer() {
        if (wakeWhisperRecognizer != null) {
            try {
                wakeWhisperRecognizer!!.javaClass.getDeclaredMethod("release").invoke(wakeWhisperRecognizer)
            } catch (_: Exception) {}
            wakeWhisperRecognizer = null
            wakeWhisperMethods = null
            wakeWhisperEngine = null
            wakeWhisperValid = false
        }
    }

    private fun buildWakeWhisperMethods(recognizer: Any): WakeWhisperMethods {
        val clsRecognizer = recognizer.javaClass
        val clsStream = Class.forName("$PKG.OfflineStream")
        return WakeWhisperMethods(
            createStream = clsRecognizer.getDeclaredMethod("createStream"),
            decode = clsRecognizer.getDeclaredMethod("decode", clsStream),
            getResult = clsRecognizer.getDeclaredMethod("getResult", clsStream),
            acceptWaveform = clsStream.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType),
            streamRelease = clsStream.getDeclaredMethod("release"),
        )
    }

    private fun resultTextFromWakeWhisper(rec: Any, stream: Any, methods: WakeWhisperMethods): String {
        val result = methods.getResult.invoke(rec, stream)!!
        val getText = result.javaClass.getDeclaredMethod("getText").also { it.isAccessible = true }
        return (getText.invoke(result) as String).trim().trimEnd('?', '.', '!', ',', ':', ';')
            .lowercase(java.util.Locale.ROOT)
    }

    /**
     * Releases the dedicated online wake-verification recognizer and clears its
     * cached method handles. Must only be called while [wakeVerifierMutex] is held
     * so a recognizer is never released mid-verification (#1440).
     */
    private fun releaseWakeRecognizer() {
        if (wakeRecognizer != null) {
            try {
                wakeRecognizer!!.javaClass.getDeclaredMethod("release").invoke(wakeRecognizer)
            } catch (_: Exception) {}
            wakeRecognizer = null
            wakeMethods = null
            wakeSpecEngine = null
            wakeSpecValid = false
        }
    }

    /**
     * Constructs the appropriate Sherpa recognizer for [spec] via reflection.
     * Must be called only from within [recognizerMutex].
     */
    private fun initRecognizer(spec: SherpaSttModelSpec): Any {
        return when (spec.recognizerKind) {
            SherpaSttModelSpec.RecognizerKind.Online -> initOnlineRecognizer(spec)
            SherpaSttModelSpec.RecognizerKind.Offline -> initOfflineRecognizer(spec)
        }
    }

    private fun initRecognizerInstance(spec: SherpaSttModelSpec): Any {
        return initOnlineRecognizer(spec)
    }

    private fun buildWakeMethods(recognizer: Any): WakeRecognizerMethods {
        val clsRecognizer = recognizer.javaClass
        val clsStream = Class.forName("$PKG.OnlineStream")
        return WakeRecognizerMethods(
            createStream = clsRecognizer.getDeclaredMethod("createStream", String::class.java),
            isReady = clsRecognizer.getDeclaredMethod("isReady", clsStream),
            decode = clsRecognizer.getDeclaredMethod("decode", clsStream),
            getResult = clsRecognizer.getDeclaredMethod("getResult", clsStream),
            acceptWaveform = clsStream.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType),
            inputFinished = clsStream.getDeclaredMethod("inputFinished"),
            streamRelease = clsStream.getDeclaredMethod("release"),
        )
    }

    private fun resultTextFromWakeMethods(rec: Any, stream: Any, methods: WakeRecognizerMethods): String {
        val result = methods.getResult.invoke(rec, stream)!!
        val getText = result.javaClass.getDeclaredMethod("getText").also { it.isAccessible = true }
        return (getText.invoke(result) as String).trim().trimEnd('?', '.', '!', ',', ':', ';')
            .lowercase(java.util.Locale.ROOT)
    }

    // ── Offline recognizer init (Whisper wake verifier, #1439) ────────────────

    /**
     * Constructs the dedicated Whisper tiny.en offline recognizer for wake-word
     * verification via reflection — mirrors [initOfflineRecognizer]'s Whisper
     * branch (OfflineWhisperModelConfig language=en/task=transcribe, numThreads=2,
     * provider=cpu, greedy_search, maxActivePaths=1).
     */
    private fun initWakeWhisperRecognizer(spec: SherpaSttModelSpec): Any {
        val clsModel      = Class.forName(spec.modelConfigClassName)
        val clsRecCfg     = Class.forName(spec.recognizerConfigClassName)
        val clsRecognizer = Class.forName(spec.recognizerClassName)

        val modelConfig = clsModel.getDeclaredConstructor().newInstance()
        setProperty(modelConfig, "tokens", sttFile(tokensFileForSpec(spec)).absolutePath)
        setProperty(modelConfig, "numThreads", 2)
        setProperty(modelConfig, "provider", "cpu")

        val clsWhisper = Class.forName("$PKG.OfflineWhisperModelConfig")
        val whisperConfig = clsWhisper.getDeclaredConstructor().newInstance().also {
            setProperty(it, "encoder", sttFile("sherpa-whisper-tiny.en-encoder.int8.onnx").absolutePath)
            setProperty(it, "decoder", sttFile("sherpa-whisper-tiny.en-decoder.int8.onnx").absolutePath)
            setProperty(it, "language", "en")
            setProperty(it, "task", "transcribe")
        }
        setProperty(modelConfig, "whisper", whisperConfig)

        val recConfig = clsRecCfg.getDeclaredConstructor().newInstance().also {
            setProperty(it, "modelConfig", modelConfig)
            setProperty(it, "decodingMethod", "greedy_search")
            setProperty(it, "maxActivePaths", 1)
        }

        val ctor = clsRecognizer.getConstructor(
            android.content.res.AssetManager::class.java, clsRecCfg
        )
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(null, recConfig)
    }

    // ── Online recognizer init (Zipformer / Paraformer) ────────────────────────

    private fun initWakeOnlineRecognizer(spec: SherpaSttModelSpec): Any {
        val clsFeature    = Class.forName("$PKG.FeatureConfig")
        val clsTransducer = Class.forName("$PKG.OnlineTransducerModelConfig")
        val clsModel      = Class.forName(spec.modelConfigClassName)
        val clsEndpoint   = Class.forName("$PKG.EndpointConfig")
        val clsRecCfg     = Class.forName(spec.recognizerConfigClassName)
        val clsRecognizer = Class.forName(spec.recognizerClassName)

        val featConfig = clsFeature.getDeclaredConstructor().newInstance().also {
            setProperty(it, "sampleRate", SAMPLE_RATE)
            setProperty(it, "featureDim", 80)
        }

        val modelConfig = clsModel.getDeclaredConstructor().newInstance()
        when (spec.engine) {
            VoiceInputEngine.SherpaZipformer -> {
                val transducerConfig = clsTransducer.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "encoder", sttFile("sherpa-stt-encoder.int8.onnx").absolutePath)
                    setProperty(it, "decoder", sttFile("sherpa-stt-decoder.int8.onnx").absolutePath)
                    setProperty(it, "joiner",  sttFile("sherpa-stt-joiner.int8.onnx").absolutePath)
                }
                setProperty(modelConfig, "transducer", transducerConfig)
                setProperty(modelConfig, "tokens", sttFile("sherpa-stt-tokens.txt").absolutePath)
            }
            VoiceInputEngine.SherpaParaformer -> {
                val clsParaformer = Class.forName("$PKG.OnlineParaformerModelConfig")
                val paraformerConfig = clsParaformer.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "encoder", sttFile("sherpa-paraformer-encoder.int8.onnx").absolutePath)
                    setProperty(it, "decoder", sttFile("sherpa-paraformer-decoder.int8.onnx").absolutePath)
                }
                setProperty(modelConfig, "paraformer", paraformerConfig)
                setProperty(modelConfig, "tokens", sttFile("sherpa-paraformer-tokens.txt").absolutePath)
            }
            else -> throw IllegalStateException("Unexpected online engine: ${spec.engine}")
        }

        setProperty(modelConfig, "numThreads", 2)
        setProperty(modelConfig, "debug", false)
        setProperty(modelConfig, "provider", "cpu")

        val endpointConfig = clsEndpoint.getDeclaredConstructor().newInstance()
        val recConfig = clsRecCfg.getDeclaredConstructor().newInstance().also {
            setProperty(it, "featConfig", featConfig)
            setProperty(it, "modelConfig", modelConfig)
            setProperty(it, "endpointConfig", endpointConfig)
            setProperty(it, "enableEndpoint", true)
            setProperty(it, "decodingMethod", "greedy_search")
            setProperty(it, "maxActivePaths", 4)
        }

        val ctor = clsRecognizer.getConstructor(android.content.res.AssetManager::class.java, clsRecCfg)
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(null, recConfig)
    }

    private fun initOnlineRecognizer(spec: SherpaSttModelSpec): Any {
        val clsFeature    = Class.forName("$PKG.FeatureConfig")
        val clsTransducer = Class.forName("$PKG.OnlineTransducerModelConfig")
        val clsModel      = Class.forName(spec.modelConfigClassName)
        val clsEndpoint   = Class.forName("$PKG.EndpointConfig")
        val clsRecCfg     = Class.forName(spec.recognizerConfigClassName)
        val clsRecognizer = Class.forName(spec.recognizerClassName)
        val clsStream     = Class.forName(spec.streamClassName)

        // ── Feature config (shared) ────────────────────────────────────────
        val featConfig = clsFeature.getDeclaredConstructor().newInstance().also {
            setProperty(it, "sampleRate", SAMPLE_RATE)
            setProperty(it, "featureDim", 80)
        }

        // ── OnlineModelConfig ────────────────────────────────────────────────
        val modelConfig = clsModel.getDeclaredConstructor().newInstance()

        when (spec.engine) {
            VoiceInputEngine.SherpaZipformer -> {
                // OnlineTransducerModelConfig for Zipformer
                val transducerConfig = clsTransducer.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "encoder", sttFile("sherpa-stt-encoder.int8.onnx").absolutePath)
                    setProperty(it, "decoder", sttFile("sherpa-stt-decoder.int8.onnx").absolutePath)
                    setProperty(it, "joiner",  sttFile("sherpa-stt-joiner.int8.onnx").absolutePath)
                }
                setProperty(modelConfig, "transducer", transducerConfig)
                setProperty(modelConfig, "tokens", sttFile("sherpa-stt-tokens.txt").absolutePath)
            }
            VoiceInputEngine.SherpaParaformer -> {
                // OnlineParaformerModelConfig for Paraformer
                val clsParaformer = Class.forName("$PKG.OnlineParaformerModelConfig")
                val paraformerConfig = clsParaformer.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "encoder", sttFile("sherpa-paraformer-encoder.int8.onnx").absolutePath)
                    setProperty(it, "decoder", sttFile("sherpa-paraformer-decoder.int8.onnx").absolutePath)
                }
                setProperty(modelConfig, "paraformer", paraformerConfig)
                setProperty(modelConfig, "tokens", sttFile("sherpa-paraformer-tokens.txt").absolutePath)
            }
            else -> throw IllegalStateException("Unexpected online engine: ${spec.engine}")
        }

        setProperty(modelConfig, "numThreads", 2)
        setProperty(modelConfig, "debug", false)
        setProperty(modelConfig, "provider", "cpu")

        val endpointConfig = clsEndpoint.getDeclaredConstructor().newInstance()
        val recConfig = clsRecCfg.getDeclaredConstructor().newInstance().also {
            setProperty(it, "featConfig", featConfig)
            setProperty(it, "modelConfig", modelConfig)
            setProperty(it, "endpointConfig", endpointConfig)
            setProperty(it, "enableEndpoint", true)
            setProperty(it, "decodingMethod", "greedy_search")
            setProperty(it, "maxActivePaths", 4)
        }

        val ctor = clsRecognizer.getConstructor(
            android.content.res.AssetManager::class.java, clsRecCfg
        )
        @Suppress("UNCHECKED_CAST")
        val instance = ctor.newInstance(null, recConfig)

        // Cache reflected methods for online recognizer.
        streamMethods.createStream = clsRecognizer.getDeclaredMethod("createStream", String::class.java)
        onlineMethods.isReady    = clsRecognizer.getDeclaredMethod("isReady", clsStream)
        onlineMethods.decode     = clsRecognizer.getDeclaredMethod("decode", clsStream)
        onlineMethods.isEndpoint = clsRecognizer.getDeclaredMethod("isEndpoint", clsStream)
        onlineMethods.getResult  = clsRecognizer.getDeclaredMethod("getResult", clsStream)
        onlineMethods.reset      = clsRecognizer.getDeclaredMethod("reset", clsStream)
        streamMethods.acceptWaveform = clsStream.getDeclaredMethod(
            "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType
        )
        streamMethods.inputFinished = clsStream.getDeclaredMethod("inputFinished")
        streamMethods.streamRelease = clsStream.getDeclaredMethod("release")

        cacheGetText(instance)

        activeSpec = spec
        recognizer = instance
        return instance
    }

    // ── Offline recognizer init (Whisper / SenseVoice) ─────────────────────────

    private fun initOfflineRecognizer(spec: SherpaSttModelSpec): Any {
        val clsModel      = Class.forName(spec.modelConfigClassName)
        val clsRecCfg     = Class.forName(spec.recognizerConfigClassName)
        val clsRecognizer = Class.forName(spec.recognizerClassName)
        val clsStream     = Class.forName(spec.streamClassName)

        // OfflineModelConfig
        val modelConfig = clsModel.getDeclaredConstructor().newInstance()
        setProperty(modelConfig, "tokens", sttFile(tokensFileForSpec(spec)).absolutePath)
        setProperty(modelConfig, "numThreads", 2)
        setProperty(modelConfig, "provider", "cpu")

        when (spec.engine) {
            VoiceInputEngine.SherpaWhisper -> {
                val clsWhisper = Class.forName("$PKG.OfflineWhisperModelConfig")
                val whisperConfig = clsWhisper.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "encoder", sttFile("sherpa-whisper-tiny.en-encoder.int8.onnx").absolutePath)
                    setProperty(it, "decoder", sttFile("sherpa-whisper-tiny.en-decoder.int8.onnx").absolutePath)
                    setProperty(it, "language", "en")
                    setProperty(it, "task", "transcribe")
                }
                setProperty(modelConfig, "whisper", whisperConfig)
            }
            VoiceInputEngine.SherpaSenseVoice -> {
                val clsSenseVoice = Class.forName("$PKG.OfflineSenseVoiceModelConfig")
                val senseVoiceConfig = clsSenseVoice.getDeclaredConstructor().newInstance().also {
                    setProperty(it, "model", sttFile("sherpa-sensevoice-model.int8.onnx").absolutePath)
                    setProperty(it, "language", "en")
                    setProperty(it, "useInverseTextNormalization", true)
                }
                setProperty(modelConfig, "senseVoice", senseVoiceConfig)
            }
            else -> throw IllegalStateException("Unexpected offline engine: ${spec.engine}")
        }

        // OfflineRecognizerConfig
        val recConfig = clsRecCfg.getDeclaredConstructor().newInstance().also {
            setProperty(it, "modelConfig", modelConfig)
            setProperty(it, "decodingMethod", "greedy_search")
            setProperty(it, "maxActivePaths", 1)
        }

        val ctor = clsRecognizer.getConstructor(
            android.content.res.AssetManager::class.java, clsRecCfg
        )
        @Suppress("UNCHECKED_CAST")
        val instance = ctor.newInstance(null, recConfig)

        // Cache reflected methods for offline recognizer.
        // OfflineRecognizer.createStream() takes no argument.
        streamMethods.createStream = clsRecognizer.getDeclaredMethod("createStream")
        offlineMethods.decode      = clsRecognizer.getDeclaredMethod("decode", clsStream)
        offlineMethods.getResult   = clsRecognizer.getDeclaredMethod("getResult", clsStream)
        streamMethods.acceptWaveform = clsStream.getDeclaredMethod(
            "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType
        )
        streamMethods.inputFinished = runCatching {
            clsStream.getDeclaredMethod("inputFinished")
        }.getOrNull()
        streamMethods.streamRelease = clsStream.getDeclaredMethod("release")

        cacheGetText(instance)

        activeSpec = spec
        recognizer = instance
        return instance
    }

    private fun tokensFileForSpec(spec: SherpaSttModelSpec): String {
        return when (spec.engine) {
            VoiceInputEngine.SherpaZipformer -> "sherpa-stt-tokens.txt"
            VoiceInputEngine.SherpaSenseVoice -> "sherpa-sensevoice-tokens.txt"
            VoiceInputEngine.SherpaWhisper -> "sherpa-whisper-tiny.en-tokens.txt"
            VoiceInputEngine.SherpaParaformer -> "sherpa-paraformer-tokens.txt"
            else -> "sherpa-stt-tokens.txt"
        }
    }

    // ── getText caching ────────────────────────────────────────────────────────

    /**
     * Creates a dummy stream and decodes to cache the getText method handle.
     * Avoids per-call getDeclaredMethod() in the hot loop.
     */
    private fun cacheGetText(recognizerInstance: Any) {
        try {
            val stream = try {
                when {
                    onlineMethods.getResult != null -> streamMethods.createStream!!.invoke(recognizerInstance, "")!!
                    offlineMethods.getResult != null -> streamMethods.createStream!!.invoke(recognizerInstance)!!
                    else -> return
                }
            } catch (_: Exception) {
                return
            }
            try {
                val getResultMethod = when {
                    onlineMethods.getResult != null -> onlineMethods.getResult
                    offlineMethods.getResult != null -> offlineMethods.getResult
                    else -> return
                }
                val dummyResult = getResultMethod!!.invoke(recognizerInstance, stream)!!
                streamMethods.getText = dummyResult.javaClass.getDeclaredMethod("getText")
                    .also { it.isAccessible = true }
            } finally {
                streamMethods.streamRelease!!.invoke(stream)
            }
        } catch (_: Exception) {
            // Non-fatal — getText will be lazily resolved in resultText methods.
        }
    }

    // ── Reflection helper ──────────────────────────────────────────────────────

    private fun setProperty(obj: Any, name: String, value: Any) {
        val setter = "set${name.replaceFirstChar { it.uppercase() }}"
        val primitiveType: Class<*>? = when (value) {
            is Int     -> Int::class.javaPrimitiveType
            is Boolean -> Boolean::class.javaPrimitiveType
            is Float   -> Float::class.javaPrimitiveType
            else       -> null
        }
        val valueClass: Class<*> = primitiveType ?: value.javaClass

        try {
            obj.javaClass.getDeclaredMethod(setter, valueClass)
                .also { it.isAccessible = true }
                .invoke(obj, value)
            return
        } catch (_: NoSuchMethodException) { /* fall through to field */ }

        try {
            obj.javaClass.getDeclaredField(name)
                .also { it.isAccessible = true }
                .set(obj, value)
        } catch (e: Exception) {
            Log.w(TAG, "setProperty($name) on ${obj.javaClass.simpleName} failed", e)
        }
    }
}