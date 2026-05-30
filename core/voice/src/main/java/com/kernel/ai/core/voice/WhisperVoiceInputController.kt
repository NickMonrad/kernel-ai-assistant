package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFormat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * VoiceInputController backed by whisper.cpp via JNI/NDK bridge.
 *
 * Uses the same CMake + JNI pattern as [jegly/Box](https://github.com/jegly/Box):
 * the native library is compiled from whisper.cpp source, exposing
 * `loadModelNative`, `transcribeNative`, and `freeModelNative` as `external` functions.
 *
 * Threading model:
 * - `startListening`/`stopListening` are serialised by [sessionMutex].
 * - Model loading is cached after first use.
 * - Audio capture runs on [Dispatchers.IO]; [_events] is a [MutableSharedFlow].
 *
 * **Design: push-to-talk / batch-only.** whisper.cpp does not produce streaming partials
 * on Android in a practical way — the entire recording is transcribed after the user stops.
 */
@Singleton
class WhisperVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInputController {

    companion object {
        private const val TAG = "WhisperSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = (0.1 * SAMPLE_RATE).toInt() // 100 ms
        private const val LISTEN_TIMEOUT_MS = 15_000L

        /** Model file name stored in external models directory. */
        const val MODEL_FILE = "ggml-tiny.bin"

        /**
         * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
         */
        fun String.containsWakePhrase(): Boolean {
            val lower = lowercase()
            val namePattern = Regex("""\b(?:hey|a)\s*(?:jandal|jandel|handel|handal|hando)\b""")
            return namePattern.containsMatchIn(lower)
        }
    }

    init {
        System.loadLibrary("whisper_jni")
    }

    // ── JNI native methods ─────────────────────────────────────────────────────

    private external fun loadModelNative(modelPath: String): Long
    private external fun transcribeNative(handle: Long, audioData: FloatArray, language: String): String
    private external fun freeModelNative(handle: Long)

    // ── Model state ────────────────────────────────────────────────────────────

    @Volatile private var nativeHandle: Long = 0L
    private val modelMutex = Mutex()

    // ── Session state ──────────────────────────────────────────────────────────

    private val sessionMutex = Mutex()
    private val recordingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var activeJob: Job? = null

    // ── Audio focus ────────────────────────────────────────────────────────────

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
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

            if (!ensureModel()) {
                return@withLock VoiceInputStartResult.Unavailable(
                    "Whisper model not available — download it from Settings → Voice."
                )
            }

            requestAudioFocus()
            val ar = createAudioRecord() ?: run {
                releaseAudioFocus()
                return@withLock VoiceInputStartResult.Unavailable(
                    "Microphone unavailable — check RECORD_AUDIO permission."
                )
            }

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
                }
            }

            VoiceInputStartResult.Started
        }

    override fun stopListening() {
        activeJob?.cancel()
        activeJob = null
    }

    // ── Audio loop ─────────────────────────────────────────────────────────────

    /**
     * Blocking audio capture loop — runs on [Dispatchers.IO].
     *
     * Records until [stopListening] is called or timeout is reached, then transcribes
     * the entire buffer via whisper.cpp JNI. Emits a single [Transcript] event.
     */
    private fun audioLoop(ar: AudioRecord, mode: VoiceCaptureMode) {
        val pcmBuffer = mutableListOf<Short>()
        val chunk = ShortArray(CHUNK_SAMPLES)
        val started = System.currentTimeMillis()

        ar.startRecording()
        try {
            while (activeJob?.isActive == true) {
                val read = ar.read(chunk, 0, chunk.size)
                if (read > 0) {
                    pcmBuffer.addAll(chunk.take(read))
                }

                // Timeout guard
                if (System.currentTimeMillis() - started > LISTEN_TIMEOUT_MS) {
                    break
                }
            }
        } finally {
            ar.stop()
        }

        if (pcmBuffer.isEmpty()) {
            _events.tryEmit(VoiceInputEvent.Error(mode, "No audio captured."))
            _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
            return
        }

        // Convert to float32 and transcribe
        val floats = pcmBuffer.map { it / 32768f }.toFloatArray()
        val transcript = transcribeNative(nativeHandle, floats, "en")

        if (transcript.isNotBlank()) {
            _events.tryEmit(VoiceInputEvent.Transcript(mode, transcript.trim()))
        } else {
            _events.tryEmit(
                VoiceInputEvent.Error(mode, "I didn't catch anything — please try again.")
            )
        }
        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
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
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
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
            audioManager.requestAudioFocus(
                { }, android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
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

    // ── Model management ───────────────────────────────────────────────────────

    private fun modelsDir(): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    private fun modelFile(): File = File(modelsDir(), MODEL_FILE)

    /**
     * Ensures the whisper.cpp native model is loaded. Returns false if the model file
     * is missing or loading fails.
     */
    private suspend fun ensureModel(): Boolean {
        if (nativeHandle != 0L) return true

        return modelMutex.withLock {
            if (nativeHandle != 0L) return@withLock true

            val file = withContext(Dispatchers.IO) { modelFile() }
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "Whisper model file missing: ${file.absolutePath}")
                false
            } else {
                try {
                    nativeHandle = loadModelNative(file.absolutePath)
                    nativeHandle != 0L
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load whisper model", e)
                    false
                }
            }
        }
    }

    /**
     * Releases the native whisper.cpp model handle. Called on controller destruction.
     */
    fun cleanup() {
        if (nativeHandle != 0L) {
            freeModelNative(nativeHandle)
            nativeHandle = 0L
        }
    }

    // ── Availability ───────────────────────────────────────────────────────────

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        modelFile().let { it.exists() && it.length() > 0 }
    }
}
