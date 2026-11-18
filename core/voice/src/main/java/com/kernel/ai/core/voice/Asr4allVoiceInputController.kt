package com.kernel.ai.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.kernel.ai.core.voice.asr4all.Asr4allEngine
import com.kernel.ai.core.voice.asr4all.Asr4allModelFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Asr4allSTT"
private const val SAMPLE_RATE = 16_000
private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val SPEECH_RMS = 0.02f
private const val TRAILING_SILENCE_CHUNKS = 15 // 1.5 s of quiet after speech ends the utterance
private const val NO_SPEECH_TIMEOUT_CHUNKS = 100 // 10 s before any speech
private const val MAX_UTTERANCE_CHUNKS = 600 // 60 s hard cap

/**
 * Streaming STT with the asr4all model (FUTO, Kotlin port of the HF Python driver). Audio runs
 * through the engine in 100 ms chunks; partials come from the streaming decode and the final
 * text from PCEC rendering after the stream is finished.
 *
 * The engine is not thread-safe and every capture runs on one job, so decode calls are
 * serialised. The engine is opened on the first start and kept until the model files change.
 */
@Singleton
class Asr4allVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInputController {

    private val _events = voiceEventFlow()
    override val events: Flow<VoiceInputEvent> = _events.asSharedFlow()

    private val _inputLevel = MutableStateFlow(0f)
    override val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private var engine: Asr4allEngine? = null
    private var activeJob: Job? = null
    private var activeStop: AtomicBoolean? = null

    fun isAvailable(): Boolean = Asr4allModelFiles.isDownloaded(context)

    @SuppressLint("MissingPermission")
    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult = startLock.withLock {
        activeJob?.join()
        if (!isAvailable()) {
            return@withLock VoiceInputStartResult.Unavailable(
                "Offline speech model is not downloaded. Download it in Settings → Voice.",
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
        }
        val eng = withContext(Dispatchers.IO) { engine ?: open().also { engine = it } }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 2 * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
        }
        val sessionId = VoiceCaptureSessionIds.allocate()
        val stop = AtomicBoolean(false)
        activeStop = stop
        _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
        activeJob = scope.launch {
            try {
                eng.reset()
                runCapture(recorder, eng, mode, sessionId, stop)
            } catch (e: Exception) {
                Log.e(TAG, "asr4all capture failed", e)
                _events.tryEmit(VoiceInputEvent.Error(mode, e.message ?: "Voice input error", sessionId))
            } finally {
                recorder.stop()
                recorder.release()
                _inputLevel.value = 0f
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
            }
        }
        VoiceInputStartResult.Started(sessionId)
    }

    private fun open(): Asr4allEngine = Asr4allEngine.open(Asr4allModelFiles.releaseDir(context))

    /** Reads the mic until the user stops, the utterance ends, or a timeout is reached. */
    private fun runCapture(
        recorder: AudioRecord,
        eng: Asr4allEngine,
        mode: VoiceCaptureMode,
        sessionId: Long,
        stop: AtomicBoolean,
    ) {
        val pcm = ShortArray(CHUNK_SAMPLES)
        recorder.startRecording()
        var heard = false
        var silentChunks = 0
        var chunks = 0
        var lastPartial = ""
        while (!stop.get() && chunks < MAX_UTTERANCE_CHUNKS) {
            val n = recorder.read(pcm, 0, pcm.size)
            if (n <= 0) break
            chunks++
            val floats = FloatArray(n)
            var sumSquares = 0.0
            for (i in 0 until n) {
                val f = pcm[i] / 32768f
                floats[i] = f
                sumSquares += f * f
            }
            val rms = sqrt(sumSquares / n).toFloat()
            _inputLevel.value = rms.coerceIn(0f, 1f)

            val partial = eng.decodeStream(floats)
            if (partial != lastPartial) {
                lastPartial = partial
                _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, partial, sessionId))
            }
            if (rms >= SPEECH_RMS) {
                if (!heard) {
                    heard = true
                    _events.tryEmit(VoiceInputEvent.SpeechDetected(mode, sessionId))
                }
                silentChunks = 0
            } else if (heard) {
                silentChunks++
                if (silentChunks >= TRAILING_SILENCE_CHUNKS) break
            } else if (chunks >= NO_SPEECH_TIMEOUT_CHUNKS) {
                break
            }
        }
        _inputLevel.value = 0f
        if (!heard) {
            if (!stop.get()) _events.tryEmit(VoiceInputEvent.Error(mode, "No speech heard. Try again.", sessionId))
            return
        }
        _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, sessionId))
        eng.finish()
        val text = eng.decodePcec(decodeTail = true).trim()
        if (text.isNotEmpty()) _events.tryEmit(VoiceInputEvent.Transcript(mode, text, sessionId))
    }

    override fun stopListening() {
        activeStop?.set(true)
    }

    override suspend fun prewarm() {
        if (!isAvailable()) return
        startLock.withLock {
            withContext(Dispatchers.IO) { if (engine == null) engine = open() }
        }
    }
}
