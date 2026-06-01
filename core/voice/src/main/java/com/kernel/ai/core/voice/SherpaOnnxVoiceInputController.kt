package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * - [startListening]/[stopListening] are serialised by [sessionMutex].
 * - Recognizer creation is serialised by [recognizerMutex]; cached and reused
 *   unless the spec changes (recreates the recognizer).
 * - The audio capture loop runs on [Dispatchers.IO]; [_events] is a [MutableSharedFlow]
 *   with a replay buffer so observers on Main never block the IO loop.
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
        private const val OFFLINE_TRAILING_SILENCE_FRAMES = 8 // 800 ms at 100 ms chunks
        private const val PKG = "com.k2fsa.sherpa.onnx"
        /**
         * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
         *
         * Matches across common ASR error modes (Handel/Handal/Jandel) and normalises case.
         */
        fun String.containsWakePhrase(): Boolean {
            val lower = lowercase()
            val namePattern = Regex("""\b(?:hey|a)\s*(?:jandal|jandel|handel|handal|hando)\b""")
            return namePattern.containsMatchIn(lower)
        }
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
     * Dedicated Zipformer recognizer for wake-word verification.
     * Lives independently from [recognizer] — never released or replaced by
     * the interactive STT session, so [transcribeBlocking] is safe to call
     * concurrently with [startListening]/[stopListening].
     */
    private val wakeRecognizerMutex = Mutex()
    @Volatile private var wakeRecognizer: Any? = null
    @Volatile private var wakeMethods: WakeRecognizerMethods? = null
    @Volatile private var wakeSpecEngine: VoiceInputEngine? = null
    @Volatile private var wakeSpecValid: Boolean = false

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

    // ── Audio focus ────────────────────────────────────────────────────────────

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    @Volatile private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // ── Events ─────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    // ── VoiceInputController ───────────────────────────────────────────────────

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        sessionMutex.withLock {
            val previousJob = activeJob
            activeJob = null
            previousJob?.cancel()
            previousJob?.join()

            val spec = resolveSpec()
            val rec = ensureRecognizer(spec) ?: return@withLock VoiceInputStartResult.Unavailable(
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
                    when (spec.recognizerKind) {
                        SherpaSttModelSpec.RecognizerKind.Online -> onlineAudioLoop(ar, mode, rec, spec)
                        SherpaSttModelSpec.RecognizerKind.Offline -> offlineAudioLoop(ar, mode, rec, spec)
                    }
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

    // ── Online audio loop (Zipformer / Paraformer) ─────────────────────────────

    @Volatile private var isRecording = false

    /**
     * Blocking audio capture loop for online (streaming) recognizers.
     * Feeds 100 ms PCM chunks to Sherpa incrementally, decodes on every chunk,
     * and emits partial results.  On endpoint or timeout, flushes and emits final text.
     */
    private fun onlineAudioLoop(
        ar: AudioRecord,
        mode: VoiceCaptureMode,
        rec: Any,
        spec: SherpaSttModelSpec,
    ) {
        val stream = streamMethods.createStream!!.invoke(rec, "") // createStream(hotwords: String)

        val pcmBuf = ShortArray(CHUNK_SAMPLES)
        val floatBuf = FloatArray(CHUNK_SAMPLES)
        var lastPartial = ""
        val started = System.currentTimeMillis()
        var exitedFromLoop = false

        try {
            ar.startRecording()
            while (isRecording) {
                val n = ar.read(pcmBuf, 0, pcmBuf.size)
                if (n <= 0) continue

                for (i in 0 until n) floatBuf[i] = pcmBuf[i] / 32768f
                val exactFloat = if (n == floatBuf.size) floatBuf else floatBuf.copyOf(n)
                streamMethods.acceptWaveform!!.invoke(stream, exactFloat, SAMPLE_RATE)

                // Drain — Sherpa may buffer multiple decodable chunks per feed.
                while (onlineMethods.isReady!!.invoke(rec, stream) as Boolean) {
                    onlineMethods.decode!!.invoke(rec, stream)
                }

                // ── Endpoint detection ─────────────────────────────────────
                if (onlineMethods.isEndpoint!!.invoke(rec, stream) as Boolean) {
                    val text = signalEndAndDrainOnline(rec, stream)
                    if (text.isNotEmpty()) {
                        _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                        lastPartial = ""
                    }
                    onlineMethods.reset!!.invoke(rec, stream)
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
                    val text = signalEndAndDrainOnline(rec, stream)
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
                val partial = resultTextOnline(rec, stream)
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, partial))
                }
            }

            if (!exitedFromLoop) {
                val text = signalEndAndDrainOnline(rec, stream)
                if (text.isNotEmpty()) {
                    _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                }
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
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
        return (getText.invoke(result) as String).trim().lowercase(java.util.Locale.ROOT)
    }

    // ── Offline audio loop (Whisper / SenseVoice) ──────────────────────────────

    /**
     * Blocking audio capture loop for offline (batch) recognizers.
     * Records until stop or timeout, then decodes the full buffer once and emits final text.
     * Does not emit partial results.
     */
    private fun offlineAudioLoop(
        ar: AudioRecord,
        mode: VoiceCaptureMode,
        rec: Any,
        spec: SherpaSttModelSpec,
    ) {
        val stream = streamMethods.createStream!!.invoke(rec)

        val pcmBuf = ShortArray(CHUNK_SAMPLES)
        val allFloats = mutableListOf<FloatArray>()
        val started = System.currentTimeMillis()
        var speechDetected = false
        var trailingSilenceFrames = 0

        try {
            ar.startRecording()
            while (isRecording) {
                val n = ar.read(pcmBuf, 0, pcmBuf.size)
                if (n <= 0) continue

                val floatChunk = FloatArray(n) { pcmBuf[it] / 32768f }
                allFloats.add(floatChunk)

                val rms = chunkRms(floatChunk)
                if (rms >= OFFLINE_SPEECH_RMS_THRESHOLD) {
                    speechDetected = true
                    trailingSilenceFrames = 0
                } else if (speechDetected) {
                    trailingSilenceFrames++
                    if (trailingSilenceFrames >= OFFLINE_TRAILING_SILENCE_FRAMES) break
                }

                if (System.currentTimeMillis() - started > LISTEN_TIMEOUT_MS) break
            }

            // Concatenate all recorded chunks and decode once.
            val totalSamples = allFloats.sumOf { it.size }
            if (totalSamples > 0) {
                val fullPcm = FloatArray(totalSamples)
                var offset = 0
                for (chunk in allFloats) {
                    System.arraycopy(chunk, 0, fullPcm, offset, chunk.size)
                    offset += chunk.size
                }

                streamMethods.acceptWaveform!!.invoke(stream, fullPcm, SAMPLE_RATE)
                streamMethods.inputFinished?.invoke(stream)
                offlineMethods.decode!!.invoke(rec, stream)

                val text = resultTextOffline(rec, stream)
                if (text.isNotEmpty()) {
                    _events.tryEmit(VoiceInputEvent.Transcript(mode, text))
                } else {
                    _events.tryEmit(
                        VoiceInputEvent.Error(mode, "I didn't catch anything — please try again.")
                    )
                }
            } else {
                _events.tryEmit(
                    VoiceInputEvent.Error(mode, "I didn't catch anything — please try again.")
                )
            }
        } finally {
            streamMethods.streamRelease!!.invoke(stream)
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
        }
    }

    private fun resultTextOffline(rec: Any, stream: Any): String {
        val result = offlineMethods.getResult!!.invoke(rec, stream)!!
        val getText = streamMethods.getText
            ?: result.javaClass.getDeclaredMethod("getText")
                .also { it.isAccessible = true; streamMethods.getText = it }
        return (getText.invoke(result) as String).trim().lowercase(java.util.Locale.ROOT)
    }

    private fun chunkRms(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (v in chunk) sum += (v * v).toDouble()
        return kotlin.math.sqrt(sum / chunk.size).toFloat()
    }

    suspend fun transcribeBlocking(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val spec = SherpaSttModelSpec.WAKE_VERIFICATION_DEFAULT
        val (rec, methods) = ensureWakeRecognizerBlocking(spec) ?: return ""
        val stream = methods.createStream.invoke(rec, "")
        return try {
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
            ""
        } finally {
            runCatching { methods.streamRelease.invoke(stream) }
        }
    }

    /**
     * Ensures a dedicated Zipformer recognizer exists for wake-word verification.
     * Uses its own mutex and dedicated method handles so the interactive STT recognizer
     * can be reinitialized independently.
     */
    private suspend fun ensureWakeRecognizerBlocking(
        spec: SherpaSttModelSpec,
    ): Pair<Any, WakeRecognizerMethods>? {
        if (wakeRecognizer != null && wakeSpecValid && wakeSpecEngine == spec.engine && wakeMethods != null) {
            return wakeRecognizer!! to wakeMethods!!
        }
        return wakeRecognizerMutex.withLock {
            if (wakeRecognizer != null && wakeSpecValid && wakeSpecEngine == spec.engine && wakeMethods != null) {
                return@withLock wakeRecognizer!! to wakeMethods!!
            }
            if (!isSpecAvailable(spec)) {
                Log.w(TAG, "Wake-word Zipformer model files missing")
                return@withLock null
            }
            try {
                val instance = initWakeOnlineRecognizer(spec)
                val methods = buildWakeMethods(instance)
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
                initRecognizer(spec)
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
        return (getText.invoke(result) as String).trim().lowercase(java.util.Locale.ROOT)
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