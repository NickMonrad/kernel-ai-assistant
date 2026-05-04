package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spike implementation of [VoiceOutputController] backed by Sherpa-ONNX + locally prepared Piper
 * English voices.
 *
 * **Design goals (spike):**
 * - Zero direct imports from `com.k2fsa.sherpa.onnx.*` — all Sherpa access goes through
 *   Java reflection so this file compiles even when the AAR has not been downloaded yet.
 * - Graceful [VoiceOutputResult.Unavailable] when either the AAR or the model assets are absent.
 * - Streams PCM floats to [AudioTrack] in chunks so the audio focus and stop() interruptibility
 *   work the same way as [AndroidTextToSpeechController].
 * - Extracts `espeak-ng-data` (and the model itself) from APK assets to [Context.getFilesDir]
 *   because Sherpa-ONNX needs real filesystem paths, not `AssetManager` URIs for those files.
 *
 * **Prerequisites (not committed):**
 * Run `scripts/setup-sherpa-tts-spike.sh` to:
 *   1. Download `sherpa-onnx-1.13.0.aar` → `third_party/sherpa-onnx/`
 *   2. Download Piper voice assets → `core/voice/src/main/assets/sherpa-tts/...`
 *
 * When the AAR/assets are absent the class initialises cleanly to [InitState.UNAVAILABLE] and
 * [FallbackVoiceOutputController] will route to [AndroidTextToSpeechController] instead.
 */
@Singleton
class SherpaOnnxVoiceOutputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : VoiceOutputController {

    // ── Coroutine scope on IO — never Main ──────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = _events.asSharedFlow()

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

            stopped = false
            requestAudioFocus()
            _events.emit(VoiceOutputEvent.SpeakingStarted(request.text))

            return@withContext try {
                // Reflect: GeneratedAudio audio = tts.generate(text, sid=0, speed=1.0f)
                val audioResult = genMethod.invoke(tts, request.text, 0, 1.0f)
                    ?: return@withContext VoiceOutputResult.Unavailable("Sherpa returned null audio.")

                val samples = reflectGetSamples(audioResult)
                val sampleRate = reflectGetSampleRate(audioResult)

                if (!stopped) {
                    playOnAudioTrack(samples, sampleRate)
                }
                releaseAudioFocus()
                _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Spoken
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa generate() failed", e)
                releaseAudioFocus()
                _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Unavailable("Sherpa generate failed: ${e.message}")
            }
        }

    override fun stop() {
        stopped = true
        releaseAudioFocus()
        scope.launch { _events.emit(VoiceOutputEvent.SpeakingStopped) }
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

        // 2. Prefer voice pack downloaded to internal storage over APK assets
        val downloadedDir = voice.voiceDir(context)
        val modelDir: File = when {
            voice.isDownloaded(context) -> {
                Log.i(TAG, "Using downloaded voice pack at ${downloadedDir.absolutePath}")
                downloadedDir
            }
            else -> {
                // 3. Fall back to APK assets (local dev path — assets are gitignored)
                val assetsSubdir = voice.assetsSubdirectory
                val assetsPresent = runCatching {
                    context.assets.list(assetsSubdir)?.isNotEmpty() == true
                }.getOrDefault(false)

                if (!assetsPresent) {
                    Log.i(TAG, "Voice pack not downloaded and not in APK assets: $assetsSubdir. " +
                        "Use Settings → Voice to download the voice pack.")
                    initState = InitState.UNAVAILABLE
                    return VoiceOutputResult.Unavailable(
                        "Voice pack not yet downloaded for ${voice.displayName}. " +
                            "Tap Download in Settings → Voice."
                    )
                }

                // Extract assets to filesystem so Sherpa gets real paths
                val extractedDir = File(context.filesDir, assetsSubdir)
                extractAssetsIfNeeded(assetsSubdir, extractedDir)
                extractedDir
            }
        }

        return try {
            val config = buildOfflineTtsConfig(modelDir)
            val configClass = Class.forName(SHERPA_OFFLINE_TTS_CONFIG_CLASS)
            val ctor = ttsClass.getConstructor(
                android.content.res.AssetManager::class.java,
                configClass,
            )
            val instance = ctor.newInstance(context.assets, config)
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
        ttsInstance = null
        generateMethod = null
        initState = InitState.UNINITIALIZED
        initializedVoice = voice
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

    // ── Asset extraction ─────────────────────────────────────────────────────

    /**
     * Copies the Piper model directory from APK assets to [destDir] on the real filesystem.
     * Skips individual files that already exist so subsequent launches are fast.
     * espeak-ng-data/ is recursed because Sherpa requires it as a real directory tree.
     */
    private fun extractAssetsIfNeeded(assetSubdir: String, destDir: File) {
        destDir.mkdirs()
        extractAssetsRecursive(assetSubdir, destDir)
    }

    private fun extractAssetsRecursive(assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: return
        destDir.mkdirs()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val isDir = context.assets.list(childAssetPath)?.isNotEmpty() == true
            if (isDir) {
                extractAssetsRecursive(childAssetPath, childDest)
            } else if (!childDest.exists()) {
                copyAsset(childAssetPath, childDest)
            }
        }
    }

    private fun copyAsset(assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { inp ->
            FileOutputStream(destFile).use { out -> inp.copyTo(out) }
        }
    }

    // ── AudioTrack playback ──────────────────────────────────────────────────

    /**
     * Writes [samples] (PCM Float32, mono) to an [AudioTrack] in streaming chunks.
     * Checks [stopped] between chunks so [stop] can interrupt mid-utterance quickly.
     */
    private fun playOnAudioTrack(samples: FloatArray, sampleRate: Int) {
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

        track.play()
        try {
            var offset = 0
            while (offset < samples.size && !stopped) {
                val end = minOf(offset + AUDIO_CHUNK_FLOATS, samples.size)
                track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
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
