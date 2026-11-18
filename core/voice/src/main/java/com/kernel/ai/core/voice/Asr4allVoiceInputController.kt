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
import com.kernel.ai.core.voice.asr4all.Asr4allEngine
import com.kernel.ai.core.voice.asr4all.Asr4allModelFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Asr4allSTT"
private const val SAMPLE_RATE = 16_000
private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val SPEECH_RMS = 0.02f
private const val NO_SPEECH_TIMEOUT_CHUNKS = 100 // 10 s without decoded text
private const val ENDPOINT_BLANK_FRAMES = 25 // 1.0 s of committed blank frames (40 ms each) after text
private const val MAX_UTTERANCE_CHUNKS = 600 // 60 s hard cap

/**
 * Streaming STT with the asr4all model (FUTO, Kotlin port of the HF Python driver).
 *
 * Capture follows the Sherpa online contract (see [SherpaOnnxVoiceInputController]):
 * - The model files are checked first. A stop, or a [isWanted] that turns false, between the
 *   call and the mic open ends the start without opening the mic, playing the cue or emitting
 *   `ListeningStarted`; it returns `Started` with a `ListeningStopped` event.
 * - The mic opens (MIC source, speech focus) before the engine loads. A reader keeps the newest
 *   [PreRollAudioBuffer.PRE_ROLL_SAMPLES] of audio while the engine loads, then the capture loop
 *   takes it through [nextCaptureChunk].
 * - The mic and focus are released by the reader's `finally`, which runs on stop, supersession,
 *   a failed engine open, or a cancelled start.
 *
 * Partials and the final text are PCEC renderings of committed decoder output, recomputed only
 * when the decoder advances. The endpoint is decode evidence: nonempty decoded text followed by
 * [ENDPOINT_BLANK_FRAMES] committed blank frames. RMS drives only the speech-detected signal and
 * the input level.
 *
 * Two ways a capture ends:
 * - [stopListening] is an explicit finalize: the utterance so far is decoded and emitted.
 * - [releaseEngine] supersedes the capture: nothing from it is emitted after release begins.
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
    /** Serialises start, prewarm and release; guards [engine]. */
    private val startLock = Mutex()
    private var engine: Asr4allEngine? = null // guarded by startLock
    private var audioFocusRequest: AudioFocusRequest? = null
    /** Guards the stop count and the mic-open decision, like Sherpa's captureLock. */
    private val captureLock = Any()
    private var stopCount = 0 // guarded by captureLock
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeStop: AtomicBoolean? = null
    @Volatile private var activeDiscard: AtomicBoolean? = null

    // Test seams (internal, asr4all only). Null in production.
    internal var availableForTest: (() -> Boolean)? = null
    internal var micGrantedForTest: Boolean? = null
    internal var recorderForTest: (() -> AudioRecord?)? = null
    internal var engineForTest: (() -> Asr4allEngine)? = null
    internal var releaseFencedForTest: (() -> Unit)? = null

    fun isAvailable(): Boolean = availableForTest?.invoke() ?: Asr4allModelFiles.isDownloaded(context)

    private fun micGranted(): Boolean = micGrantedForTest
        ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    /** A mic open with its pre-roll buffer; the reader owns the recorder until [PreRollAudioBuffer.close]. */
    private class Capture(val buffer: PreRollAudioBuffer)

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        startListening(mode) { true }

    /**
     * [isWanted] is checked together with the stop count under [captureLock], right before the
     * mic opens and again after the engine loads. Callers that supersede this start return false,
     * so no mic opens for it.
     */
    internal suspend fun startListening(mode: VoiceCaptureMode, isWanted: () -> Boolean): VoiceInputStartResult {
        val stopsAtCall = synchronized(captureLock) { stopCount }
        return startLock.withLock {
            activeJob?.join()
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
                if (stopCount != stopsAtCall || !wanted) {
                    stoppedEarly = true
                    null
                } else {
                    openCapture()?.also {
                        activeStop = stop
                        activeDiscard = discard
                        _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
                    } ?: run { micFailed = true; null }
                }
            }
            if (capture == null) {
                if (stoppedEarly) {
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                    return@withLock VoiceInputStartResult.Started(sessionId)
                }
                check(micFailed)
                return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
            }

            val eng = try {
                withContext(Dispatchers.IO) { engine ?: openOrNull()?.also { engine = it } }
            } catch (e: Throwable) {
                // Caller cancellation or an unexpected failure: release the mic and focus, then rethrow.
                capture.buffer.close()
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                throw e
            }
            if (eng == null) {
                capture.buffer.close()
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                return@withLock VoiceInputStartResult.Unavailable(
                    "Offline speech model could not be loaded. Re-download it in Settings → Voice.",
                )
            }

            synchronized(captureLock) {
                val wanted = isWanted()
                if (stopCount != stopsAtCall || !wanted || stop.get()) {
                    capture.buffer.close()
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                    return@withLock VoiceInputStartResult.Started(sessionId)
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
                        _inputLevel.value = 0f
                        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId))
                    }
                }
            }
            VoiceInputStartResult.Started(sessionId)
        }
    }

    /**
     * Opens the mic with speech focus and starts the reader that fills the pre-roll buffer. Returns
     * null, with focus released, when the mic cannot be opened. The reader's `finally` closes the
     * recorder and abandons focus, on every exit path.
     */
    @SuppressLint("MissingPermission")
    private fun openCapture(): Capture? {
        requestAudioFocus()
        val recorder = recorderForTest?.invoke() ?: createAudioRecord()
        if (recorder == null) {
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
            releaseAudioFocus()
            return null
        }
        val buffer = PreRollAudioBuffer(PreRollAudioBuffer.PRE_ROLL_SAMPLES)
        scope.launch {
            val buf = ShortArray(CHUNK_SAMPLES)
            try {
                recorder.startRecording()
                while (isActive && !buffer.isClosed) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) buffer.append(buf, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pre-roll reader error", e)
            } finally {
                buffer.close()
                stopRecorder(recorder)
                releaseAudioFocus()
            }
        }
        return Capture(buffer)
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

    private fun requestAudioFocus() {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
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
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun open(): Asr4allEngine = engineForTest?.invoke()
        ?: Asr4allEngine.open(Asr4allModelFiles.releaseDir(context))

    /** Null on a failed open (bad or partial model files). Cancellation is rethrown. */
    private fun openOrNull(): Asr4allEngine? = try {
        open()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "asr4all engine failed to open", e)
        JandalEvents.record("voice", "stt_error", "category" to SttFailure.MODEL_UNAVAILABLE)
        null
    }

    /**
     * Runs the capture loop over the pre-roll chunks, then live audio. Final text is decoded
     * text, not an RMS result: any nonempty PCEC output is emitted.
     */
    private fun runCapture(
        buffer: PreRollAudioBuffer,
        eng: Asr4allEngine,
        mode: VoiceCaptureMode,
        sessionId: Long,
        stop: AtomicBoolean,
        discard: AtomicBoolean,
    ) {
        val preRoll = ArrayDeque(buffer.attach())
        var heard = false
        var chunks = 0
        var lastFrames = 0
        var lastPcec = ""
        while (!stop.get() && chunks < MAX_UTTERANCE_CHUNKS) {
            val pcm = nextCaptureChunk(preRoll, buffer) ?: break
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
            if (rms >= SPEECH_RMS && !heard) {
                heard = true
                _events.tryEmit(VoiceInputEvent.SpeechDetected(mode, sessionId))
            }

            eng.decodeStream(floats) // advances the decoder; its raw text is not used for partials
            if (eng.decodedFrameCount != lastFrames) {
                lastFrames = eng.decodedFrameCount
                val text = eng.decodePcec(decodeTail = true).trim()
                if (text.isNotEmpty() && text != lastPcec) {
                    lastPcec = text
                    if (!discard.get()) _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, text, sessionId))
                }
            }

            if (lastPcec.isNotEmpty() && eng.trailingBlankFrames >= ENDPOINT_BLANK_FRAMES) break
            if (lastPcec.isEmpty() && chunks >= NO_SPEECH_TIMEOUT_CHUNKS) break
        }
        _inputLevel.value = 0f
        // Superseded by releaseEngine: no error, no finish, no transcript.
        if (discard.get()) return
        if (lastPcec.isEmpty() && !stop.get()) {
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
            _events.tryEmit(VoiceInputEvent.Error(mode, "No speech heard. Try again.", sessionId))
            return
        }
        _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, sessionId))
        eng.finish()
        val text = eng.decodePcec(decodeTail = true).trim()
        // A release may arrive during the final decode; a superseded transcript is dropped.
        if (discard.get()) return
        if (text.isNotEmpty()) {
            JandalEvents.record("voice", "stt_result", "chars" to text.length)
            _events.tryEmit(VoiceInputEvent.Transcript(mode, text, sessionId))
        } else if (!stop.get()) {
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.NO_SPEECH)
            _events.tryEmit(VoiceInputEvent.Error(mode, "No speech heard. Try again.", sessionId))
        }
    }

    override fun stopListening() {
        synchronized(captureLock) {
            stopCount++
            activeStop?.set(true)
        }
    }

    override suspend fun prewarm() {
        if (!isAvailable()) return
        startLock.withLock {
            withContext(Dispatchers.IO) { if (engine == null) engine = openOrNull() }
        }
    }

    /**
     * Supersedes any capture without emitting its result, waits for its job to end, and closes
     * the engine. Flags and the stop count are set before taking [startLock] so an in-flight start
     * stops promptly, and set again under the lock. Idempotent; a later start reopens the engine.
     */
    suspend fun releaseEngine() {
        supersedeActive()
        releaseFencedForTest?.invoke()
        startLock.withLock {
            supersedeActive()
            activeJob?.join()
            withContext(Dispatchers.IO) {
                engine?.close()
                engine = null
            }
        }
    }

    private fun supersedeActive() = synchronized(captureLock) {
        stopCount++
        activeDiscard?.set(true)
        activeStop?.set(true)
    }
}
