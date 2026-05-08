package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spike implementation of [VoiceOutputController] backed by Sherpa-ONNX + downloaded Piper
 * English voices.
 *
 * **Design goals (spike):**
 * - Zero direct imports from `com.k2fsa.sherpa.onnx.*` — all Sherpa access goes through
 *   Java reflection so this file compiles even when the AAR has not been downloaded yet.
 * - Graceful [VoiceOutputResult.Unavailable] when either the AAR or the downloaded voice pack is
 *   absent.
 * - Streams PCM floats to [AudioTrack] in chunks so the audio focus and stop() interruptibility
 *   work the same way as [AndroidTextToSpeechController].
 *
 * **Prerequisites (not committed):**
 * Run `scripts/setup-sherpa-tts-spike.sh` to:
 *   1. Download `sherpa-onnx-1.13.0.aar` → `third_party/sherpa-onnx/`
 *
 * Voice packs are provisioned on device from Settings → Voice and extracted to [Context.getFilesDir]
 * because Sherpa-ONNX needs real filesystem paths, not `AssetManager` URIs for those files.
 *
 * When the AAR/voice pack are absent the class initialises cleanly to [InitState.UNAVAILABLE] and
 * [FallbackVoiceOutputController] will route to [AndroidTextToSpeechController] instead.
 */
@Singleton
class SherpaOnnxVoiceOutputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : VoiceOutputController {
    private data class ActiveStreamingPlayback(
        val token: Long,
        val chunks: Channel<StreamingChunk>,
        val worker: Job,
    )

    private data class StreamingChunk(
        val text: String,
        val isFinal: Boolean,
    )

    // ── Coroutine scope on IO — never Main ──────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = _events.asSharedFlow()

    /** Live speech rate from user preferences; default 0.85. Range 0.5–1.5. */
    private val sherpaSpeed: StateFlow<Float> = voiceOutputPreferences.sherpaSpeed
        .stateIn(scope, SharingStarted.Eagerly, 0.85f)

    /** Live pitch from user preferences; default 1.0. Range 0.5–2.0. */
    private val sherpaPitch: StateFlow<Float> = voiceOutputPreferences.voicePitch
        .stateIn(scope, SharingStarted.Eagerly, 1.0f)

    /** PCM amplification gain; default 1.5 to compensate for Sherpa's quiet output. Range 0.5–3.0. */
    private val sherpaGain: StateFlow<Float> = voiceOutputPreferences.voiceGain
        .stateIn(scope, SharingStarted.Eagerly, 1.5f)

    /** Active speaker ID for multi-speaker voices (e.g. VCTK). Stored in preferences, default 0. */
    private val activeSpeakerId: StateFlow<Int> = voiceOutputPreferences.activeSpeakerId
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // ── Lifecycle state ──────────────────────────────────────────────────────
    private enum class InitState { UNINITIALIZED, AVAILABLE, UNAVAILABLE }

    @Volatile private var initState = InitState.UNINITIALIZED
    @Volatile private var initializedVoice: SherpaPiperVoice? = null

    /** Reflected handle to `OfflineTts` instance; non-null iff [initState] == AVAILABLE. */
    @Volatile private var ttsInstance: Any? = null

    /** Reflected `generate(String, Int, Float): GeneratedAudio` method. */
    @Volatile private var generateMethod: java.lang.reflect.Method? = null

    // ── Playback cancellation ────────────────────────────────────────────────
    @Volatile private var stopped = false
    @Volatile private var nextPlaybackToken = 0L
    @Volatile private var activeStreamingPlayback: ActiveStreamingPlayback? = null
    private val playbackLock = Any()

    /**
     * Generation counter for non-streaming [speak] calls. Incremented before each new
     * [playOnAudioTrack] invocation so any previous write loop can detect it has been superseded
     * and abort, preventing stale audio from bleeding into the new playback.
     */
    private val nonStreamingPlaybackGeneration = AtomicLong(0)

    // ── AudioFocus ───────────────────────────────────────────────────────────
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    // ── VoiceOutputController ────────────────────────────────────────────────

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        initialize(voiceOutputPreferences.selectedSherpaVoice.first())
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult =
        withContext(Dispatchers.IO) {
            val voice = voiceOutputPreferences.selectedSherpaVoice.first()
            val initResult = initialize(voice)
            if (initResult is VoiceOutputResult.Unavailable) return@withContext initResult

            val tts = ttsInstance
                ?: return@withContext VoiceOutputResult.Unavailable("Sherpa TTS instance missing.")
            val genMethod = generateMethod
                ?: return@withContext VoiceOutputResult.Unavailable("Sherpa generate() not found.")

            stopped = true
            clearStreamingPlayback()
            stopped = false
            val myGeneration = nonStreamingPlaybackGeneration.incrementAndGet()

            // Track whether SpeakingStarted was emitted so SpeakingStopped is only sent when
            // it has a matching start — stops the back-and-forth loop from firing on a stopped
            // or failed synthesis where no audio was ever played.
            var speakingStarted = false
            // For single-speaker voices (speakerCount == 1) always use sid=0 — the stored
            // activeSpeakerId may have been set while a multi-speaker voice (e.g. VCTK) was
            // active and must not bleed into single-speaker synthesis.
            val effectiveSid = if (voice.speakerCount > 1)
                activeSpeakerId.value.coerceIn(0, voice.speakerCount - 1) else 0
            return@withContext try {
                // Reflect: GeneratedAudio audio = tts.generate(text, sid, speed)
                // Synthesis can take 1-3s for medium models, longer for high-quality ones;
                // only emit SpeakingStarted once audio is ready and playback is about to begin.
                val synthStart = System.currentTimeMillis()
                val audioResult = genMethod.invoke(tts, request.text, effectiveSid, sherpaSpeed.value)
                    ?: return@withContext VoiceOutputResult.Unavailable("Sherpa returned null audio.")
                Log.d(TAG, "Sherpa synthesis: ${System.currentTimeMillis() - synthStart}ms for " +
                    "${request.text.length} chars, voice=${voice.name}, sid=$effectiveSid")

                val samples = reflectGetSamples(audioResult)
                val sampleRate = reflectGetSampleRate(audioResult)

                if (!stopped) {
                    requestAudioFocus()
                    _events.emit(VoiceOutputEvent.SpeakingStarted(request.text))
                    speakingStarted = true
                    playOnAudioTrack(samples, sampleRate, myGeneration)
                }
                releaseAudioFocus()
                if (speakingStarted) _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Spoken
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa generate() failed", e)
                releaseAudioFocus()
                if (speakingStarted) _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Unavailable("Sherpa generate failed: ${e.message}")
            }
        }

    override suspend fun openStreamingSession(
        request: VoiceSpeakRequest,
    ): VoiceOutputStreamingSession = withContext(Dispatchers.IO) {
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        val initResult = initialize(voice)
        if (initResult is VoiceOutputResult.Unavailable) {
            return@withContext object : VoiceOutputStreamingSession {
                override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult = initResult
            }
        }

        val playbackToken = allocatePlaybackToken()
        stopped = true
        clearStreamingPlayback()
        stopped = false
        val chunks = Channel<StreamingChunk>(Channel.UNLIMITED)
        val worker = scope.launch {
            runStreamingPlayback(
                playbackToken = playbackToken,
                request = request,
                chunks = chunks,
                speakerCount = voice.speakerCount,
            )
        }
        synchronized(playbackLock) {
            activeStreamingPlayback = ActiveStreamingPlayback(
                token = playbackToken,
                chunks = chunks,
                worker = worker,
            )
        }

        return@withContext object : VoiceOutputStreamingSession {
            private var closed = false

            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                if (closed) return VoiceOutputResult.Spoken
                val normalized = text.trim()
                if (normalized.isNotBlank()) {
                    chunks.send(StreamingChunk(text = normalized, isFinal = isFinal))
                } else if (isFinal) {
                    chunks.close()
                }
                if (isFinal) {
                    closed = true
                    chunks.close()
                }
                return VoiceOutputResult.Spoken
            }
        }
    }

    override fun stop() {
        stopped = true
        releaseAudioFocus()
        clearStreamingPlayback()
    }

    // ── Initialisation ───────────────────────────────────────────────────────

    /**
     * Idempotent lazy initialisation.  Cached in [initState] after first attempt.
     * Returns [VoiceOutputResult.Spoken] when Sherpa is ready, [VoiceOutputResult.Unavailable]
     * otherwise (AAR missing, assets missing, or any reflection error).
     */
    private fun initialize(voice: SherpaPiperVoice): VoiceOutputResult {
        if (initializedVoice != voice) {
            resetForVoice(voice)
        }
        return when (initState) {
            InitState.AVAILABLE -> VoiceOutputResult.Spoken
            InitState.UNAVAILABLE -> VoiceOutputResult.Unavailable(unavailableMessage(voice))
            InitState.UNINITIALIZED -> doInitialize(voice)
        }
    }

    private fun doInitialize(voice: SherpaPiperVoice): VoiceOutputResult {
        // 1. Check AAR is on classpath via Class.forName (no import needed)
        val ttsClass = try {
            Class.forName(SHERPA_OFFLINE_TTS_CLASS)
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Sherpa-ONNX not on classpath — AAR not present in APK.")
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable(
                "Sherpa-ONNX runtime not available for ${voice.displayName}."
            )
        }

        // 2. Use the voice pack downloaded to internal storage.
        val downloadedDir = voice.voiceDir(context)
        val modelDir: File = if (voice.isDownloaded(context)) {
            Log.i(TAG, "Using downloaded voice pack at ${downloadedDir.absolutePath}")
            downloadedDir
        } else {
            Log.i(
                TAG,
                "Voice pack not downloaded for ${voice.displayName}. Use Settings → Voice to download it.",
            )
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable(
                "Voice pack not yet downloaded for ${voice.displayName}. Tap Download in Settings → Voice.",
            )
        }

        return try {
            val config = buildOfflineTtsConfig(modelDir)
            val configClass = Class.forName(SHERPA_OFFLINE_TTS_CONFIG_CLASS)
            val ctor = ttsClass.getConstructor(
                android.content.res.AssetManager::class.java,
                configClass,
            )
            // Always use Sherpa's file-backed native path. Both downloaded packs and legacy
            // asset packs are normalised onto the real filesystem before init.
            val instance = ctor.newInstance(null, config)
            // Cache reflected generate() — signature: generate(String, int, float)
            val gen = ttsClass.getDeclaredMethod(
                "generate",
                String::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            ttsInstance = instance
            generateMethod = gen
            initState = InitState.AVAILABLE
            Log.i(TAG, "Sherpa-ONNX TTS initialised — voice: ${voice.downloadKey} at ${modelDir.absolutePath}")
            VoiceOutputResult.Spoken
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa-ONNX TTS init failed", e)
            initState = InitState.UNAVAILABLE
            VoiceOutputResult.Unavailable("Sherpa-ONNX init failed for ${voice.displayName}: ${e.message}")
        }
    }

    private fun resetForVoice(voice: SherpaPiperVoice) {
        clearStreamingPlayback()
        ttsInstance = null
        generateMethod = null
        initState = InitState.UNINITIALIZED
        initializedVoice = voice
    }

    private suspend fun runStreamingPlayback(
        playbackToken: Long,
        request: VoiceSpeakRequest,
        chunks: Channel<StreamingChunk>,
        speakerCount: Int,
    ) {
        val tts = ttsInstance ?: return
        val genMethod = generateMethod ?: return
        // For single-speaker voices always use sid=0; for multi-speaker, clamp to valid range.
        val effectiveSid = if (speakerCount > 1)
            activeSpeakerId.value.coerceIn(0, speakerCount - 1) else 0
        var emittedStarted = false
        var requestedAudioFocus = false
        try {
            for (chunk in chunks) {
                if (stopped || !isStreamingPlaybackActive(playbackToken)) break
                if (!emittedStarted) {
                    _events.emit(VoiceOutputEvent.SpeakingStarted(request.text.ifBlank { chunk.text }))
                    emittedStarted = true
                }
                if (!requestedAudioFocus) {
                    requestAudioFocus()
                    requestedAudioFocus = true
                }
                val synthStart = System.currentTimeMillis()
                val audioResult = try {
                    genMethod.invoke(tts, chunk.text, effectiveSid, sherpaSpeed.value)
                        ?: return
                } catch (e: Exception) {
                    Log.e(TAG, "Sherpa streaming generate() failed", e)
                    return
                }
                Log.d(TAG, "Sherpa streaming chunk: ${System.currentTimeMillis() - synthStart}ms " +
                    "for ${chunk.text.length} chars, sid=$effectiveSid")
                val samples = reflectGetSamples(audioResult)
                val sampleRate = reflectGetSampleRate(audioResult)
                if (!stopped && isStreamingPlaybackActive(playbackToken)) {
                    playOnAudioTrack(samples, sampleRate)
                }
                if (chunk.isFinal) {
                    break
                }
            }
        } finally {
            releaseAudioFocus()
            if (finishStreamingPlayback(playbackToken)) {
                _events.emit(VoiceOutputEvent.SpeakingStopped)
            }
        }
    }

    private fun allocatePlaybackToken(): Long = synchronized(playbackLock) {
        nextPlaybackToken += 1L
        nextPlaybackToken
    }

    private fun isStreamingPlaybackActive(playbackToken: Long): Boolean = synchronized(playbackLock) {
        activeStreamingPlayback?.token == playbackToken
    }

    private fun finishStreamingPlayback(playbackToken: Long): Boolean = synchronized(playbackLock) {
        val active = activeStreamingPlayback ?: return@synchronized false
        if (active.token != playbackToken) return@synchronized false
        activeStreamingPlayback = null
        true
    }

    private fun clearStreamingPlayback(): Boolean = synchronized(playbackLock) {
        val active = activeStreamingPlayback ?: return@synchronized false
        activeStreamingPlayback = null
        active.chunks.close()
        active.worker.cancel()
        true
    }

    // ── Config construction via reflection ───────────────────────────────────

    /**
     * Builds `OfflineTtsConfig` entirely through reflection so no Sherpa import is needed.
     *
     * Sherpa-ONNX Kotlin data classes all have Kotlin-default–value constructors (every field
     * has a default), so we call the no-arg/all-defaults constructor then mutate the mutable
     * (`var`) properties via their setter methods (`setModel(String)`, etc.).
     *
     * Field layout targeted:
     * ```
     * OfflineTtsVitsModelConfig { model, tokens, dataDir, noiseScale, noiseScaleW, lengthScale }
     * OfflineTtsModelConfig     { vits, numThreads, debug, provider }
     * OfflineTtsConfig          { model, ruleFsts, maxNumSentences }
     * ```
     */
    private fun buildOfflineTtsConfig(modelDir: File): Any {
        val vitsClass = Class.forName(SHERPA_VITS_MODEL_CONFIG_CLASS)
        val modelConfigClass = Class.forName(SHERPA_MODEL_CONFIG_CLASS)
        val configClass = Class.forName(SHERPA_OFFLINE_TTS_CONFIG_CLASS)

        // OfflineTtsVitsModelConfig — all-defaults constructor
        val vitsConfig = newInstance(vitsClass)
        setProperty(vitsConfig, "model", File(modelDir, SHERPA_MODEL_ONNX_FILE).absolutePath)
        setProperty(vitsConfig, "tokens", File(modelDir, SHERPA_TOKENS_FILE).absolutePath)
        setProperty(vitsConfig, "dataDir", File(modelDir, SHERPA_ESPEAK_DATA_DIR).absolutePath)

        // OfflineTtsModelConfig
        val modelConfig = newInstance(modelConfigClass)
        setProperty(modelConfig, "vits", vitsConfig)
        setProperty(modelConfig, "numThreads", 2)
        setProperty(modelConfig, "debug", false)

        // OfflineTtsConfig
        val config = newInstance(configClass)
        setProperty(config, "model", modelConfig)
        return config
    }

    /**
     * Instantiates a Kotlin class whose constructor has all-default parameters.
     * Tries the no-arg form first; if absent (Kotlin doesn't generate a *true* no-arg unless
     * annotated with `@JvmOverloads` or no-arg Gradle plugin) falls back to the Kotlin synthetic
     * default constructor `(…, int mask, DefaultConstructorMarker marker)`.
     */
    private fun newInstance(cls: Class<*>): Any {
        // Attempt 1: genuine no-arg constructor (generated by no-arg compiler plugin or @JvmOverloads)
        cls.declaredConstructors.firstOrNull { it.parameterCount == 0 }?.let { ctor ->
            ctor.isAccessible = true
            return ctor.newInstance()
        }
        // Attempt 2: Kotlin synthetic default constructor with trailing (int, DefaultConstructorMarker?)
        // Signature: (T1, T2, ..., int, DefaultConstructorMarker?) where int is the bit-mask.
        val synthetic = cls.declaredConstructors.minByOrNull { it.parameterCount }
            ?: error("No usable constructor found for ${cls.name}")
        synthetic.isAccessible = true
        val argCount = synthetic.parameterCount
        // Fill leading params with null/0/false; second-to-last is the int mask (0 = use all defaults);
        // last is DefaultConstructorMarker (pass null — Kotlin accepts null for the marker).
        val args = arrayOfNulls<Any>(argCount).also { it[argCount - 2] = 0 }
        return synthetic.newInstance(*args)
    }

    /**
     * Sets a mutable Kotlin property on [obj] via its generated JVM setter (`setXxx`).
     * Falls back to direct field access if no setter is found.
     */
    private fun setProperty(obj: Any, name: String, value: Any) {
        val setterName = "set${name.replaceFirstChar { it.uppercase() }}"
        val valueClass: Class<*> = when (value) {
            is String -> String::class.java
            is Int -> Int::class.javaPrimitiveType!!
            is Float -> Float::class.javaPrimitiveType!!
            is Boolean -> Boolean::class.javaPrimitiveType!!
            else -> value.javaClass
        }
        try {
            val setter = obj.javaClass.getDeclaredMethod(setterName, valueClass)
            setter.isAccessible = true
            setter.invoke(obj, value)
        } catch (_: NoSuchMethodException) {
            // Fall back to direct field mutation (Kotlin backing field)
            try {
                val field = obj.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.set(obj, value)
            } catch (e2: Exception) {
                Log.w(TAG, "setProperty($name) failed on ${obj.javaClass.simpleName}", e2)
            }
        }
    }

    // ── GeneratedAudio reflection helpers ────────────────────────────────────

    private fun reflectGetSamples(audio: Any): FloatArray {
        return try {
            audio.javaClass.getDeclaredMethod("getSamples")
                .also { it.isAccessible = true }
                .invoke(audio) as FloatArray
        } catch (_: NoSuchMethodException) {
            audio.javaClass.getDeclaredField("samples")
                .also { it.isAccessible = true }
                .get(audio) as FloatArray
        }
    }

    private fun reflectGetSampleRate(audio: Any): Int {
        return try {
            audio.javaClass.getDeclaredMethod("getSampleRate")
                .also { it.isAccessible = true }
                .invoke(audio) as Int
        } catch (_: NoSuchMethodException) {
            audio.javaClass.getDeclaredField("sampleRate")
                .also { it.isAccessible = true }
                .getInt(audio)
        }
    }

    // ── AudioTrack playback ──────────────────────────────────────────────────

    /**
     * Writes [samples] (PCM Float32, mono) to an [AudioTrack] in streaming chunks.
     * Checks [stopped] between chunks so [stop] can interrupt mid-utterance quickly.
     *
     * [generation] is the value of [nonStreamingPlaybackGeneration] at the time this invocation
     * was started. If a newer non-streaming [speak] call has incremented the counter before this
     * loop iteration begins, the loop aborts — preventing stale audio from bleeding into the new
     * playback. Pass the default (-1) from the streaming path, which uses its own token guard.
     */
    private fun playOnAudioTrack(samples: FloatArray, sampleRate: Int, generation: Long = -1L) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, AUDIO_CHUNK_FLOATS * Float.SIZE_BYTES * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Apply PCM gain before playback. AudioTrack.setVolume() is capped at 1.0 by the
        // framework, so amplification above unity must be done in the sample domain.
        // Gain is applied per-chunk into a reusable scratch buffer to avoid a full-buffer
        // allocation on every TTS call.
        val gain = sherpaGain.value
        // Only apply PlaybackParams when pitch differs from unity — routing all audio through
        // Android's SONIC time-stretcher (even at pitch=1.0) reduces perceived loudness.
        // Speed is fixed at 1.0 here; Sherpa already controls tempo at synthesis time.
        val pitch = sherpaPitch.value
        val chunk = if (gain != 1.0f) FloatArray(AUDIO_CHUNK_FLOATS) else null
        try {
            track.play()
            if (pitch != 1.0f) {
                track.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1.0f)
            }
            var offset = 0
            while (offset < samples.size && !stopped &&
                (generation < 0L || nonStreamingPlaybackGeneration.get() == generation)
            ) {
                val end = minOf(offset + AUDIO_CHUNK_FLOATS, samples.size)
                val len = end - offset
                if (chunk != null) {
                    for (i in 0 until len) {
                        chunk[i] = (samples[offset + i] * gain).coerceIn(-1.0f, 1.0f)
                    }
                    track.write(chunk, 0, len, AudioTrack.WRITE_BLOCKING)
                } else {
                    track.write(samples, offset, len, AudioTrack.WRITE_BLOCKING)
                }
                offset = end
            }
            if (!stopped) track.stop() else track.pause()
        } finally {
            track.release()
        }
    }

    // ── AudioFocus ───────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "SherpaOnnx audio focus not granted: $result")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun unavailableMessage(voice: SherpaPiperVoice): String =
        "Sherpa-ONNX TTS is not available for ${voice.displayName}. " +
            "Download the voice pack in Settings → Voice."

    /**
     * Called by [SherpaVoicePackDownloadManager] after a voice pack is successfully downloaded.
     *
     * If this voice was previously [InitState.UNAVAILABLE] (pack was missing), resets the
     * state to [InitState.UNINITIALIZED] so the next [speak] call re-initialises Sherpa
     * using the newly extracted files — without requiring a voice-selection change.
     */
    fun markVoiceAvailable(voice: SherpaPiperVoice) {
        if (initState == InitState.UNAVAILABLE && (initializedVoice == voice || initializedVoice == null)) {
            Log.i(TAG, "Voice pack available for ${voice.displayName} — resetting init state for retry.")
            resetForVoice(voice)
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "KernelAI"
        // Sherpa-ONNX class names — accessed only via reflection so no AAR import required
        const val SHERPA_PKG = "com.k2fsa.sherpa.onnx"
        const val SHERPA_OFFLINE_TTS_CLASS = "$SHERPA_PKG.OfflineTts"
        const val SHERPA_OFFLINE_TTS_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsConfig"
        const val SHERPA_MODEL_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsModelConfig"
        const val SHERPA_VITS_MODEL_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsVitsModelConfig"

        /** Floats per AudioTrack write chunk (~92 ms at 22050 Hz). */
        const val AUDIO_CHUNK_FLOATS = 2048
    }
}
