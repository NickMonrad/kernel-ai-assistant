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
import kotlinx.coroutines.withContext

private const val TAG = "Asr4allSTT"
private const val SAMPLE_RATE = 16_000
private const val CHUNK_SAMPLES = SAMPLE_RATE / 10 // 100 ms
private const val SPEECH_RMS = 0.02f
private const val NO_SPEECH_TIMEOUT_CHUNKS = 100 // 10 s without decoded text
private const val ENDPOINT_BLANK_FRAMES = 25 // 1.0 s of committed blank frames (40 ms each) after text
private const val MAX_UTTERANCE_CHUNKS = 600 // 60 s hard cap

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

/**
 * Streaming STT with the asr4all model (FUTO, Kotlin port of the HF Python driver).
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
 * Partials are PCEC renderings of committed decoder output, recomputed only when the decoder
 * advances. PCEC text is not speech evidence: it holds the last open token until the stream is
 * finished. The endpoint and the no-speech decision therefore use the raw decoder text
 * ([Asr4allEngine.decodeStream]); the final text is produced only after [Asr4allEngine.finish].
 *
 * Two ways a capture ends:
 * - [stopListening] is an explicit finalize: the utterance so far is decoded and emitted.
 * - [supersedeCapture] (and [releaseEngine]) discards: nothing from it is emitted.
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
    /** Serialises start, prewarm, release and exclusive file work; guards [engine]. */
    private val startLock = Mutex()
    private var engine: Asr4allEngine? = null // guarded by startLock
    /** Guards the stop count and the mic-open decision, like Sherpa's captureLock. */
    private val captureLock = Any()
    private var stopCount = 0 // guarded by captureLock
    private var admissionEpoch = 0L // guarded by captureLock
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeStop: AtomicBoolean? = null
    @Volatile private var activeDiscard: AtomicBoolean? = null
    /** Most recent mic open; its reader must end before the next mic open or engine release. */
    @Volatile private var lastCapture: Capture? = null
    @Volatile private var activeCapture: Capture? = null

    // Test seams (internal, asr4all only). Null in production.
    internal var availableForTest: (() -> Boolean)? = null
    internal var micGrantedForTest: Boolean? = null
    internal var recorderForTest: (() -> AudioRecord?)? = null
    internal var engineForTest: (() -> Asr4allEngine)? = null
    internal var releaseFencedForTest: (() -> Unit)? = null

    fun isAvailable(): Boolean = availableForTest?.invoke() ?: Asr4allModelFiles.isDownloaded(context)

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
            activeJob?.join()
            lastCapture?.reader?.join()
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
                    openCapture(sessionId)?.also {
                        onSession(sessionId)
                        lastCapture = it
                        activeCapture = it
                        activeStop = stop
                        activeDiscard = discard
                        _events.tryEmit(VoiceInputEvent.ListeningStarted(mode, sessionId))
                    } ?: run { micFailed = true; null }
                }
            }
            if (capture == null) {
                if (stoppedEarly) return@withLock VoiceInputStartResult.Cancelled()
                check(micFailed)
                return@withLock VoiceInputStartResult.Unavailable("Microphone unavailable — check RECORD_AUDIO permission.")
            }

            // The load runs on the controller scope, so a cancelled caller leaves the await at
            // once and ends the mic before the blocking native open returns. The load is still
            // awaited, non-cancellably, so the start lock is not released while it runs.
            val loading = scope.async(Dispatchers.IO) { engine ?: openOrNull()?.also { engine = it } }
            val eng = try {
                loading.await()
            } catch (e: Throwable) {
                halt(capture)
                withContext(NonCancellable) {
                    capture.reader.join()
                }
                _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = true))
                withContext(NonCancellable) {
                    loading.join()
                }
                throw e
            }
            if (eng == null) {
                halt(capture)
                capture.reader.join()
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
                capture.reader.join()
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
                    capture.reader.join()
                    _inputLevel.value = 0f
                    _events.tryEmit(VoiceInputEvent.ListeningStopped(mode, sessionId, cancelled = discard.get()))
                }
            }
            VoiceInputStartResult.Started(sessionId)
        }
    }

    /**
     * Opens the mic with its own focus hold and starts its reader. Returns null, with the focus
     * released, when the mic cannot be opened. The reader releases the recorder and this
     * capture's focus on every exit path, and nothing else.
     */
    @SuppressLint("MissingPermission")
    private fun openCapture(sessionId: Long): Capture? {
        val focus = requestAudioFocus()
        val recorder = recorderForTest?.invoke() ?: createAudioRecord()
        if (recorder == null) {
            JandalEvents.record("voice", "stt_error", "category" to SttFailure.MIC_UNAVAILABLE)
            releaseAudioFocus(focus)
            return null
        }
        val capture = Capture(sessionId, PreRollAudioBuffer(PreRollAudioBuffer.PRE_ROLL_SAMPLES), recorder, focus)
        capture.reader = scope.launch { readLoop(capture) }
        return capture
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
     * Runs the capture loop over the pre-roll chunks, then live audio, until the buffer ends
     * (explicit stop drains what was already captured), the endpoint is reached, or the
     * no-speech timeout expires. A discard ends it at once with nothing emitted.
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
        var rawHeard = false // the decoder has committed nonempty text
        var heard = false
        var chunks = 0
        var lastFrames = 0
        var lastPcec = ""
        while (!discard.get() && chunks < MAX_UTTERANCE_CHUNKS) {
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

            val raw = eng.decodeStream(floats)
            if (raw.isNotBlank()) rawHeard = true
            if (eng.decodedFrameCount != lastFrames) {
                lastFrames = eng.decodedFrameCount
                val text = eng.decodePcec(decodeTail = true).trim()
                if (text.isNotEmpty() && text != lastPcec) {
                    lastPcec = text
                    if (!discard.get()) _events.tryEmit(VoiceInputEvent.PartialTranscript(mode, text, sessionId))
                }
            }

            if (rawHeard && eng.trailingBlankFrames >= ENDPOINT_BLANK_FRAMES) break
            if (!rawHeard && chunks >= NO_SPEECH_TIMEOUT_CHUNKS) break
        }
        _inputLevel.value = 0f
        // Superseded: no finish, no error, no transcript.
        if (discard.get()) return
        if (rawHeard) _events.tryEmit(VoiceInputEvent.SpeechEnded(mode, sessionId))
        // Drain before deciding: PCEC holds the last open token until the stream is finished.
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
        return startLock.withLock {
            if (!isCurrent()) return@withLock false
            withContext(Dispatchers.IO) { if (engine == null) engine = openOrNull() }
            true
        }
    }

    override suspend fun prewarm() {
        val epoch = admissionEpochNow()
        prewarmIfCurrent { epoch == admissionEpochNow() }
    }

    /**
     * Discards the current capture, if any, without emitting it, and ends its mic now. Callers
     * that begin a newer start or switch engines call this before waiting for the start lock.
     */
    fun supersedeCapture(): Unit = synchronized(captureLock) {
        stopCount++
        admissionEpoch++
        activeDiscard?.set(true)
        activeStop?.set(true)
        activeCapture?.let(::halt)
    }

    /**
     * The current admission epoch. Every supersede bumps it, and so does an engine release
     * (selection switch or deletion). A start admitted under an older epoch never opens the mic.
     */
    fun admissionEpochNow(): Long = synchronized(captureLock) { admissionEpoch }

    /**
     * Supersedes any capture without emitting its result, waits for its job and reader to end,
     * and closes the engine. Idempotent; a later start reopens the engine.
     */
    suspend fun releaseEngine() = withEngineReleased {}

    /**
     * Releases the engine only if the selection is still away from asr4all after acquiring the
     * delegate start lock. [beforeRelease] invalidates selector admissions at that same fence.
     */
    suspend fun releaseEngineIf(
        isStillUnselected: suspend () -> Boolean,
        beforeRelease: () -> Unit,
    ): Boolean = startLock.withLock {
        if (!isStillUnselected()) return@withLock false
        beforeRelease()
        supersedeCapture()
        activeJob?.join()
        lastCapture?.reader?.join()
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
        }
        true
    }

    /**
     * Runs [block] with no capture and no engine open: the capture is discarded, its job and
     * reader have ended, and the engine is closed, all under the start lock. Use this before
     * deleting model files so no start can open them during the deletion.
     */
    suspend fun <T> withEngineReleased(block: () -> T): T {
        supersedeCapture()
        releaseFencedForTest?.invoke()
        return startLock.withLock {
            supersedeCapture()
            activeJob?.join()
            lastCapture?.reader?.join()
            withContext(Dispatchers.IO) {
                engine?.close()
                engine = null
            }
            block()
        }
    }
}
