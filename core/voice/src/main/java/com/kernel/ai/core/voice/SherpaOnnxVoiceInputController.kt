package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * VoiceInputController backed by Sherpa-ONNX [OnlineRecognizer] (streaming Zipformer) accessed
 * entirely via Java reflection — zero compile-time dependency on `com.k2fsa.sherpa.onnx.*`.
 *
 * The Sherpa-ONNX AAR is a runtime dependency in :app; :core:voice compiles without it.
 *
 * Threading model:
 * - [startListening]/[stopListening] are serialised by [sessionMutex].
 * - [ensureRecognizer] is serialised by [recognizerMutex]; the recognizer is created once and
 *   reused across sessions.
 * - The audio capture loop runs on [Dispatchers.IO]; [_events] is a [MutableSharedFlow] with
 *   a replay buffer so observers on Main never block the IO loop.
 */
@Singleton
class SherpaOnnxVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInputController {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SherpaSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = (0.1 * SAMPLE_RATE).toInt() // 100 ms per chunk
        private const val LISTEN_TIMEOUT_MS = 15_000L

        private const val SHERPA_PKG = "com.k2fsa.sherpa.onnx"
        private const val CLS_FEATURE     = "$SHERPA_PKG.FeatureConfig"
        private const val CLS_TRANSDUCER  = "$SHERPA_PKG.OnlineTransducerModelConfig"
        private const val CLS_MODEL       = "$SHERPA_PKG.OnlineModelConfig"
        private const val CLS_ENDPOINT    = "$SHERPA_PKG.EndpointConfig"
        private const val CLS_REC_CFG     = "$SHERPA_PKG.OnlineRecognizerConfig"
        private const val CLS_RECOGNIZER  = "$SHERPA_PKG.OnlineRecognizer"
        private const val CLS_STREAM      = "$SHERPA_PKG.OnlineStream"


        // Filesystem file names — prefixed "sherpa-stt-" to match KernelModel entries in
        // core:inference.  Absolute paths resolved at runtime from sttModelsDir().
        private const val ENCODER_FILE = "sherpa-stt-encoder.int8.onnx"
        private const val DECODER_FILE = "sherpa-stt-decoder.int8.onnx"
        private const val JOINER_FILE  = "sherpa-stt-joiner.int8.onnx"
        private const val TOKENS_FILE  = "sherpa-stt-tokens.txt"

        /**
         * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
         *
         * Matches across common ASR error modes (Handel/Handal/Jandel) and normalises case.
         * Designed to be used as the acceptance predicate in the [WakeWordDetector] `verifyWindow`
         * callback:
         *
         * ```kotlin
         * // In WakeWordService.onStartCommand, after #821 + #985 are merged to main:
         * wakeWordDetector.start(
         *     onDetected = { handleDetection() },
         *     verifyWindow = { pcm ->
         *         with(SherpaOnnxVoiceInputController) {
         *             sherpaOnnxVoiceInputController.transcribeBlocking(pcm).containsWakePhrase()
         *         }
         *     },
         * )
         * ```
         */
        fun String.containsWakePhrase(): Boolean {
            val lower = lowercase()
            // Accept "hey" or "a" (common ASR substitution for "hey") before the name.
            val namePattern = Regex("""\b(?:hey|a)\s*(?:jandal|jandel|handel|handal|hando)\b""")
            return namePattern.containsMatchIn(lower)
        }
    }

    // ── Reflected recogniser state ─────────────────────────────────────────────

    @Volatile private var recognizer: Any? = null
    private val recognizerMutex = Mutex()

    /** Reflected method handles — populated once in [initRecognizer]. */
    @Volatile private var mCreateStream: java.lang.reflect.Method? = null
    @Volatile private var mAcceptWaveform: java.lang.reflect.Method? = null
    @Volatile private var mInputFinished: java.lang.reflect.Method? = null
    @Volatile private var mIsReady: java.lang.reflect.Method? = null
    @Volatile private var mDecode: java.lang.reflect.Method? = null
    @Volatile private var mIsEndpoint: java.lang.reflect.Method? = null
    @Volatile private var mGetResult: java.lang.reflect.Method? = null
    @Volatile private var mReset: java.lang.reflect.Method? = null
    @Volatile private var mStreamRelease: java.lang.reflect.Method? = null

    // ── Session state ──────────────────────────────────────────────────────────

    /** Guards the entire start/stop lifecycle to prevent overlapping sessions. */
    private val sessionMutex = Mutex()
    private val recordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeJob: Job? = null

    // ── Audio focus ────────────────────────────────────────────────────────────

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    // ── Events ─────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    // ── VoiceInputController ───────────────────────────────────────────────────

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        sessionMutex.withLock {
            // Cancel any previous session and wait for it to fully unwind — prevents
            // a new AudioRecord from being created while the old one is still releasing.
            val previousJob = activeJob
            activeJob = null
            previousJob?.cancel()
            previousJob?.join()

            val rec = ensureRecognizer() ?: return@withLock VoiceInputStartResult.Unavailable(
                "Sherpa-ONNX STT model not available — download it from Settings → Voice."
            )

            requestAudioFocus()
            val ar = createAudioRecord() ?: run {
                releaseAudioFocus()
                return@withLock VoiceInputStartResult.Unavailable(
                    "Microphone unavailable — check RECORD_AUDIO permission."
                )
            }

            isRecording = true
            _events.tryEmit(VoiceInputEvent.ListeningStarted(mode))

            activeJob = recordingScope.launch {
                try {
                    audioLoop(ar, mode)
                } catch (e: Exception) {
                    Log.e(TAG, "Audio loop error", e)
                    _events.tryEmit(VoiceInputEvent.Error(mode, e.message ?: "Voice input error"))
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
                } finally {
                    stopAudioRecord(ar)
                    releaseAudioFocus()
                    isRecording = false
                }
            }

            VoiceInputStartResult.Started
        }

    override fun stopListening() {
        isRecording = false
        activeJob?.cancel()
        activeJob = null
    }

    // ── Audio loop ─────────────────────────────────────────────────────────────

    @Volatile private var isRecording = false

    /**
     * Blocking audio capture loop — must run on [Dispatchers.IO].
     *
     * Design:
     * - Feeds 100 ms PCM chunks to the Sherpa streaming recognizer.
     * - Decodes by draining `while (isReady)` (not a single `if`) to avoid frame drops.
     * - On endpoint: flushes remaining frames via [signalEndAndDrain], emits [Transcript], then
     *   resets the stream and continues listening for the next utterance in the same session.
     *   An endpoint with empty text (silence-triggered) resets without emitting.
     * - Timeout: flushes and emits whatever was recognised so far (may be empty → Error).
     * - [stopListening] sets [isRecording] = false; the loop exits cleanly after the current
     *   chunk and flushes any partial result.
     */
    private fun audioLoop(ar: AudioRecord, mode: VoiceCaptureMode) {
        val rec = recognizer ?: return
        val stream = mCreateStream!!.invoke(rec, "") // createStream(hotwords: String)

        val pcmBuf = ShortArray(CHUNK_SAMPLES)
        val floatBuf = FloatArray(CHUNK_SAMPLES)
        var lastPartial = ""
        val started = System.currentTimeMillis()
        // Tracks whether the loop exited via endpoint or timeout (events already emitted).
        // If false, the loop was stopped externally and the post-loop block must flush + stop.
        var exitedFromLoop = false

        try {
            ar.startRecording()
            while (isRecording) {
                val n = ar.read(pcmBuf, 0, pcmBuf.size)
                if (n <= 0) continue

                // Convert exactly n samples; avoid feeding stale tail of the buffer.
                for (i in 0 until n) floatBuf[i] = pcmBuf[i] / 32768f
                val exactFloat = if (n == floatBuf.size) floatBuf else floatBuf.copyOf(n)
                mAcceptWaveform!!.invoke(stream, exactFloat, SAMPLE_RATE)

                // Drain — Sherpa may buffer multiple decodable chunks per feed.
                while (mIsReady!!.invoke(rec, stream) as Boolean) {
                    mDecode!!.invoke(rec, stream)
                }

                // ── Endpoint detection ─────────────────────────────────────
                if (mIsEndpoint!!.invoke(rec, stream) as Boolean) {
                    val text = signalEndAndDrain(rec, stream)
                    if (text.isNotEmpty()) {
                        _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                        lastPartial = ""
                    }
                    // Whether or not we got text, reset for the next utterance.
                    mReset!!.invoke(rec, stream)
                    // If we got text the upper layer will call stopListening(); otherwise keep
                    // listening — the caller controls session lifetime, not the endpoint rule.
                    if (text.isNotEmpty()) {
                        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
                        exitedFromLoop = true
                        isRecording = false
                        break
                    }
                    continue
                }

                // ── Timeout ────────────────────────────────────────────────
                if (System.currentTimeMillis() - started > LISTEN_TIMEOUT_MS) {
                    val text = signalEndAndDrain(rec, stream)
                    if (text.isNotEmpty()) {
                        _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                    } else {
                        _events.tryEmit(
                            VoiceInputEvent.Error(mode, "I didn't catch anything — please try again.")
                        )
                    }
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
                    exitedFromLoop = true
                    isRecording = false
                    break
                }

                // ── Partial transcript ─────────────────────────────────────
                val partial = resultText(rec, stream)
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, partial))
                }
            }

            // Stopped externally — flush whatever was in-flight; always signal ListeningStopped
            // so callers (e.g. WakeWordService) can reliably re-arm even on an empty utterance.
            if (!exitedFromLoop) {
                val text = signalEndAndDrain(rec, stream)
                if (text.isNotEmpty()) {
                    _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                }
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            }
        } finally {
            mStreamRelease!!.invoke(stream)
        }
    }

    /**
     * Signals end-of-input to Sherpa and drains the decoder, returning the final trimmed text.
     * Sherpa requires this before reading the final result to flush tail frames.
     */
    private fun signalEndAndDrain(rec: Any, stream: Any): String {
        mInputFinished!!.invoke(stream)
        while (mIsReady!!.invoke(rec, stream) as Boolean) {
            mDecode!!.invoke(rec, stream)
        }
        return resultText(rec, stream)
    }

    private fun resultText(rec: Any, stream: Any): String =
        mGetResult!!.invoke(rec, stream)
            .let { result ->
                result.javaClass.getDeclaredMethod("getText")
                    .also { it.isAccessible = true }
                    .invoke(result) as String
            }.trim()

    // ── Synchronous transcription (wake word verification) ─────────────────────

    /**
     * Transcribes a pre-captured PCM buffer synchronously on the calling thread.
     *
     * Intended for use as the [WakeWordDetector.start] `verifyWindow` callback, which is
     * invoked synchronously on the wake word detector thread when confidence crosses the
     * LOW_THRESHOLD.  The caller checks [containsWakePhrase] on the returned text:
     *
     * ```kotlin
     * // In WakeWordService.onStartCommand, once #821 and #985 are both on main:
     * wakeWordDetector.start(
     *     onDetected = { handleDetection() },
     *     verifyWindow = { pcm ->
     *         with(SherpaOnnxVoiceInputController) {
     *             sherpaOnnxVoiceInputController.transcribeBlocking(pcm).containsWakePhrase()
     *         }
     *     },
     * )
     * ```
     *
     * **Thread safety:** safe to call from any thread; acquires no coroutine machinery.
     * [ensureRecognizerBlocking] caches the recognizer on first call, so repeat calls are cheap.
     *
     * @param pcm 16 kHz mono PCM in int16 (the format produced by [OnnxWakeWordDetector]'s
     *            ring buffer). Converted to float32 internally.
     * @return The trimmed transcript, or an empty string if STT is unavailable or fails.
     */
    fun transcribeBlocking(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val rec = ensureRecognizerBlocking() ?: return ""
        // Create the stream outside try so the finally block can always release it.
        val stream = mCreateStream!!.invoke(rec, "")
        return try {
            val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
            mAcceptWaveform!!.invoke(stream, floats, SAMPLE_RATE)
            mInputFinished!!.invoke(stream)
            var iters = 0
            while (mIsReady!!.invoke(rec, stream) as Boolean) {
                mDecode!!.invoke(rec, stream)
                // Guard: Sherpa should need at most a handful of decode passes per utterance.
                // A stuck loop here would stall the wakeword detector thread indefinitely.
                if (++iters > 500) break
            }
            resultText(rec, stream)
        } catch (e: Exception) {
            Log.e(TAG, "transcribeBlocking failed", e)
            ""
        } finally {
            // Always release the native stream, even on exception.
            runCatching { mStreamRelease!!.invoke(stream) }
        }
    }

    /**
     * Blocking (non-suspending) variant of [ensureRecognizer] for use from non-coroutine threads.
     * Uses [kotlinx.coroutines.runBlocking] — only safe to call from a plain thread,
     * not from within a coroutine dispatcher.
     */
    private fun ensureRecognizerBlocking(): Any? {
        recognizer?.let { return it }
        return try {
            kotlinx.coroutines.runBlocking { ensureRecognizer() }
        } catch (e: Exception) {
            Log.e(TAG, "ensureRecognizerBlocking failed", e)
            null
        }
    }

    // ── AudioRecord helpers ────────────────────────────────────────────────────

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
            maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 2), // 2 bytes/sample, 2 chunks headroom
        )
        // Release on failure — takeIf drops the object without calling release(), leaking
        // the underlying audio handle on OEMs that allocate it eagerly in the constructor.
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
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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

    // ── STT model file paths ──────────────────────────────────────────────────

    /**
     * Returns the shared external models directory, mirroring [KernelModel.localFile()].
     * Falls back to internal storage when external is unavailable.
     */
    private fun sttModelsDir(): java.io.File =
        context.getExternalFilesDir("models") ?: java.io.File(context.filesDir, "models")

    private fun sttFile(fileName: String): java.io.File = java.io.File(sttModelsDir(), fileName)

    // ── Availability ───────────────────────────────────────────────────────────

    /**
     * Returns true when all four STT model files exist in the shared external models directory.
     * Safe to call without triggering reflection.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE).all { name ->
            sttFile(name).let { it.exists() && it.length() > 0 }
        }
    }

    // ── Lazy recogniser init ───────────────────────────────────────────────────

    /**
     * Constructs and caches the [OnlineRecognizer] exactly once.
     * Thread-safe via [recognizerMutex]. Returns null on any failure.
     */
    private suspend fun ensureRecognizer(): Any? {
        recognizer?.let { return it }
        return recognizerMutex.withLock {
            // Re-check after acquiring lock — another coroutine may have initialised.
            recognizer?.let { return@withLock it }

            if (!isAvailable()) {
                Log.w(TAG, "STT model files missing")
                return@withLock null
            }

            try {
                initRecognizer()
            } catch (e: Exception) {
                Log.e(TAG, "Recognizer init failed", e)
                null
            }
        }
    }

    /**
     * Performs the actual reflected construction of [OnlineRecognizer].
     * Must be called only from within [recognizerMutex].
     *
     * Config objects all expose public no-arg constructors in Sherpa-ONNX 1.13.0
     * (verified against the upstream Kotlin API and Java demo). We use those directly
     * rather than the synthetic DefaultConstructorMarker approach.
     */
    private fun initRecognizer(): Any {
        val clsFeature    = Class.forName(CLS_FEATURE)
        val clsTransducer = Class.forName(CLS_TRANSDUCER)
        val clsModel      = Class.forName(CLS_MODEL)
        val clsEndpoint   = Class.forName(CLS_ENDPOINT)
        val clsRecCfg     = Class.forName(CLS_REC_CFG)
        val clsRecognizer = Class.forName(CLS_RECOGNIZER)
        val clsStream     = Class.forName(CLS_STREAM)

        // Build config bottom-up using public no-arg constructors + property setters.
        val featConfig = clsFeature.getDeclaredConstructor().newInstance().also {
            setProperty(it, "sampleRate", SAMPLE_RATE)
            setProperty(it, "featureDim", 80)
        }
        val transducerConfig = clsTransducer.getDeclaredConstructor().newInstance().also {
            setProperty(it, "encoder", sttFile(ENCODER_FILE).absolutePath)
            setProperty(it, "decoder", sttFile(DECODER_FILE).absolutePath)
            setProperty(it, "joiner",  sttFile(JOINER_FILE).absolutePath)
        }
        val modelConfig = clsModel.getDeclaredConstructor().newInstance().also {
            setProperty(it, "transducer", transducerConfig)
            setProperty(it, "tokens", sttFile(TOKENS_FILE).absolutePath)
            setProperty(it, "numThreads", 2)
            setProperty(it, "debug",      false)
            setProperty(it, "provider",   "cpu")
        }
        val endpointConfig = clsEndpoint.getDeclaredConstructor().newInstance()
        val recConfig = clsRecCfg.getDeclaredConstructor().newInstance().also {
            setProperty(it, "featConfig",      featConfig)
            setProperty(it, "modelConfig",     modelConfig)
            setProperty(it, "endpointConfig",  endpointConfig)
            setProperty(it, "enableEndpoint",  true)
            setProperty(it, "decodingMethod",  "greedy_search")
            setProperty(it, "maxActivePaths",  4)
        }

        // OnlineRecognizer(config) — absolute filesystem paths; no AssetManager needed.
        val ctor = clsRecognizer.getConstructor(clsRecCfg)
        val instance = ctor.newInstance(recConfig)

        // Cache reflected methods. createStream takes a hotwords String (Sherpa 1.13.0 API).
        mCreateStream    = clsRecognizer.getDeclaredMethod("createStream", String::class.java)
        mIsReady         = clsRecognizer.getDeclaredMethod("isReady",      clsStream)
        mDecode          = clsRecognizer.getDeclaredMethod("decode",        clsStream)
        mIsEndpoint      = clsRecognizer.getDeclaredMethod("isEndpoint",    clsStream)
        mGetResult       = clsRecognizer.getDeclaredMethod("getResult",     clsStream)
        mReset           = clsRecognizer.getDeclaredMethod("reset",         clsStream)
        mAcceptWaveform  = clsStream.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
        mInputFinished   = clsStream.getDeclaredMethod("inputFinished")
        mStreamRelease   = clsStream.getDeclaredMethod("release")

        recognizer = instance
        return instance
    }

    // ── Reflection helper ──────────────────────────────────────────────────────

    /**
     * Sets a mutable property on [obj] via its JVM setter or direct field access.
     * Handles [String], [Int], [Boolean], and arbitrary object types.
     */
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
