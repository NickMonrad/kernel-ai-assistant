package com.kernel.ai.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.FloatBuffer
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Diagnostic summaries are only emitted when the tag's DEBUG level is enabled.
 * Checking at this cadence keeps the production detector hot loop allocation-free.
 */
private const val DIAGNOSTIC_REPORT_INTERVAL_MILLIS = 15 * 60 * 1_000L
private const val DIAGNOSTIC_REPORT_CHECK_FRAMES = 128L

// ── Audio parameters ─────────────────────────────────────────────────────────
/** openWakeWord requires 16 kHz, mono, 16-bit PCM — non-negotiable. */
private const val SAMPLE_RATE = WAKE_WORD_SAMPLE_RATE
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

// ── Frame / chunk sizes ───────────────────────────────────────────────────────
/**
 * openWakeWord processes audio in 80ms frames = 1280 samples at 16kHz.
 * This is the fundamental unit fed to the melspectrogram frontend.
 */
private const val FRAME_SAMPLES = WAKE_WORD_FRAME_SAMPLES

/**
 * Number of mel-spectrogram rows produced by melspectrogram.onnx for one 1280-sample chunk.
 * Empirically verified: input [1, 1280] → output [1, 1, 5, 32], so 5 rows per chunk.
 */
private const val MEL_ROWS_PER_CHUNK = 5

/** Number of mel frequency bins — mel model output dim 3. */
private const val MEL_BINS = 32

/**
 * The embedding backbone (embedding_model.onnx) expects input shape [batch, 76, 32, 1].
 * 76 mel rows = 15.2 chunks = ~1,216ms of audio history.
 */
private const val MEL_RING_SIZE = 76

// ── Embedding ring buffer ─────────────────────────────────────────────────────
/**
 * Number of embedding frames fed to the classifier at each step.
 *
 * hey_jandal.onnx input shape: [1, 16, 96] — confirmed from model introspection.
 * Each embedding covers ~1.2s of audio (76 mel rows × 80ms / 5 rows per chunk).
 * 16 overlapping frames yield ~2.4s of effective receptive field, not 19.5s.
 */
private const val EMBEDDING_FRAMES = 16
private const val EMBEDDING_DIM = 96

// ── PCM verification ring buffer ─────────────────────────────────────────────
/**
 * Number of PCM samples to retain for STT verification on a LOW_THRESHOLD crossing.
 * [WAKE_WORD_VERIFY_WINDOW_S] seconds at 16 kHz = 48 000 samples ≈ 96 KB.
 * Allocated once at start(); zero heap churn during detection.
 */
private val VERIFY_RING_SAMPLES = WAKE_WORD_VERIFY_WINDOW_S * SAMPLE_RATE // 48 000

// ── Model asset paths ─────────────────────────────────────────────────────────
/**
 * Shared openWakeWord preprocessing models — download from the openWakeWord
 * GitHub releases page and place in assets/models/wakeword/.
 *
 *   https://github.com/dscripka/openWakeWord/releases
 *   → melspectrogram.onnx  (~170 KB)
 *   → embedding_model.onnx (~35 MB, Google Speech Embedding)
 *
 * The classifier is trained by the #984 pipeline and placed at the third path.
 */
private const val ASSET_MELSPECTROGRAM = "models/wakeword/melspectrogram.onnx"
private const val ASSET_EMBEDDING      = "models/wakeword/embedding_model.onnx"
private const val ASSET_CLASSIFIER     = "models/wakeword/hey_jandal.onnx"

/**
 * Always-on wake word detector implementing the openWakeWord 3-stage ONNX pipeline,
 * with an optional dual-threshold STT verification path (issue #986).
 *
 * ## Pipeline
 * ```
 * AudioRecord (16kHz mono)
 *   → 80ms chunks (1280 samples)
  *   → 80ms chunks (1280 samples) → Stage 1: mel spectrogram (5 rows per chunk)
  *   → mel ring buffer (last 76 rows) — ~1.2s of acoustic context
  *   → [Stage 2] embedding_model.onnx   — [1,76,32,1] mel patch → 96-dim embedding vector
  *   → embedding ring buffer (last 16 frames) — ~19.5s of context
  *   → [Stage 3] hey_jandal.onnx        — [1,16,96] embedding window → confidence [0,1]
 *       confidence ≥ highThreshold                    → onDetected() immediately
 *       lowThreshold ≤ confidence < highThreshold     → verifyWindow(pcmRing) → onDetected() if true
 *       confidence < lowThreshold (or no verifier)    → ignore
 * ```
 *
 * ## Dual-threshold verification (issue #986)
 *
 * When a [verifyWindow] callback is supplied to [start], a secondary (lower)
 * confidence threshold becomes active.  Crossings in the secondary band trigger STT
 * verification before activating the assistant, reducing false positives while keeping
 * the fast path fast for high-confidence detections.
 *
 * The verifier receives the last [WAKE_WORD_VERIFY_WINDOW_S] seconds of raw PCM
 * (16kHz mono int16) as a [ShortArray] and is called synchronously on the detector
 * thread.  A typical verifier runs Vosk/Android STT on the buffer and checks for
 * a "hey jandal"-like transcript.
 *
 * ## Model contracts
 *
  * ### Stage 1 — melspectrogram.onnx
  * - Input:  `float32[1, 1280]` — one 80ms frame of normalised PCM in [-1, 1]
  * - Output: `float32[1, 1, 5, 32]` — 5 mel rows × 32 bins per 80ms chunk
  *
  * ### Stage 2 — embedding_model.onnx (Google Speech Embedding)
  * - Input:  `float32[1, 76, 32, 1]` — ring of 76 mel rows (last ~1.2s)
  * - Output: `float32[1, 1, 1, 96]` — one 96-dim embedding vector
  *
  * ### Stage 3 — hey_jandal.onnx (trained by #984)
  * - Input:  `float32[1, 16, 96]` — sliding window of 16 embedding frames
  * - Output: `float32[1, 1]` — wake word confidence in [0, 1]
  * - File:   assets/models/wakeword/hey_jandal.onnx
 *
 * ## Availability
 * [isAvailable] returns false and [start] is a no-op when any model file is absent,
 * so the app ships fine before the models are ready.  Placing all three files in
 * assets/models/wakeword/ activates the feature automatically.
 */
@Singleton
class OnnxWakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordPreferences: WakeWordPreferences,
) : WakeWordDetector {

    private data class ModelBytes(
        val melspectrogram: ByteArray,
        val embedding: ByteArray,
        val classifier: ByteArray,
    )

    /**
     * Loaded once on first access.  Both [isAvailable] and [start] read this field;
     * a single lazy ensures the 35 MB embedding asset is read from disk exactly once.
     */
    private val modelBytes: ModelBytes? by lazy { loadModelBytes() }

    override val isAvailable: Boolean
        get() = modelBytes != null

    /**
     * Guards the detection thread: true while the thread is running or the detected
     * callback is executing.  Using [AtomicBoolean] makes the stop/detect/start
     * transitions race-free without a synchronised block.
     */
    private val running = AtomicBoolean(false)

    /**
     * Held so [stop] can call [AudioRecord.stop] to unblock the [AudioRecord.read] call
     * in the detection thread. [AudioRecord.read] does not respond to [Thread.interrupt] on
     * Android — setting [running] to false and interrupting the thread is not enough; the
     * thread stays wedged in native code until the next 80ms audio frame arrives, which
     * means [AudioRecord.release] is delayed by up to 80ms plus ONNX inference time.
     * Stopping the recorder here causes the next [AudioRecord.read] to return an error
     * (ERROR_INVALID_OPERATION) which exits the inner read loop immediately.
     */
    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var detectionThread: Thread? = null


    private fun loadModelBytes(): ModelBytes? {
        return runCatching {
            ModelBytes(
                melspectrogram = context.assets.open(ASSET_MELSPECTROGRAM).use { it.readBytes() },
                embedding      = context.assets.open(ASSET_EMBEDDING).use { it.readBytes() },
                classifier     = context.assets.open(ASSET_CLASSIFIER).use { it.readBytes() },
            )
        }.getOrElse { e ->
            if (e is java.io.FileNotFoundException) {
                Log.d(TAG, "WakeWordDetector: model(s) not yet available — feature disabled until #984 ships (missing: ${e.message})")
            } else {
                Log.w(TAG, "WakeWordDetector: unexpected error loading model assets", e)
            }
            null
        }
    }

/** Returns true if the app currently holds RECORD_AUDIO permission. */

    fun hasMicrophonePermission(): Boolean {
        return runCatching {
            context.packageManager.checkPermission(
                android.Manifest.permission.RECORD_AUDIO,
                context.packageName,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    override fun start(onDetected: () -> Unit, verifyWindow: ((ShortArray) -> Boolean)?) {
        val bytes = modelBytes
        if (bytes == null) {
            Log.d(TAG, "WakeWordDetector: start() called but model(s) absent — no-op")
            return
        }
        if (!hasMicrophonePermission()) {
            Log.w(TAG, "WakeWordDetector: RECORD_AUDIO not granted — start suppressed")
            return
        }
        if (!running.compareAndSet(false, true)) return

        val highThreshold = runBlocking { wakeWordPreferences.confidenceThreshold.first() }
        val lowThreshold  = if (verifyWindow != null) {
            runBlocking { wakeWordPreferences.lowConfidenceThreshold.first() }
                .coerceAtMost(highThreshold)
        } else {
            highThreshold // verifier absent → collapse dual-band to single threshold
        }
        val silenceRmsThreshold = runBlocking { wakeWordPreferences.silenceRmsThreshold.first() }
            .coerceAtLeast(0f)
        val silenceHangoverFrames = secondsToFrames(
            runBlocking { wakeWordPreferences.silenceHangoverSeconds.first() }
                .coerceAtLeast(WAKE_WORD_FRAME_DURATION_SECONDS),
        )
        val replayFrames = secondsToFrames(
            runBlocking { wakeWordPreferences.silenceRearmSeconds.first() }
                .coerceIn(WAKE_WORD_FRAME_DURATION_SECONDS, WAKE_WORD_MAX_REPLAY_SECONDS),
        )
        val maxSilenceSkipFrames = secondsToFrames(WAKE_WORD_MAX_SILENCE_SKIP_SECONDS)

        Log.i(TAG, "WakeWordDetector: starting 3-stage ONNX pipeline — " +
            "highThreshold=$highThreshold" +
            if (verifyWindow != null) " lowThreshold=$lowThreshold (STT verification active)" else "")

        detectionThread = Thread(
            {
                runDetectionLoop(
                    bytes = bytes,
                    highThreshold = highThreshold,
                    lowThreshold = lowThreshold,
                    silenceRmsThreshold = silenceRmsThreshold,
                    silenceHangoverFrames = silenceHangoverFrames,
                    replayFrames = replayFrames,
                    maxSilenceSkipFrames = maxSilenceSkipFrames,
                    verifyWindow = verifyWindow,
                    onDetected = onDetected,
                )
            },
            "wake-word-detector",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { activeAudioRecord?.stop() }
        runCatching { detectionThread?.interrupt() }
        detectionThread = null
        Log.d(TAG, "WakeWordDetector: stopped")
    }
    @Suppress("LongMethod")
    @SuppressLint("MissingPermission")
    private fun runDetectionLoop(
        bytes: ModelBytes,
        highThreshold: Float,
        lowThreshold: Float,
        silenceRmsThreshold: Float,
        silenceHangoverFrames: Int,
        replayFrames: Int,
        maxSilenceSkipFrames: Int,
        verifyWindow: ((ShortArray) -> Boolean)?,
        onDetected: () -> Unit,
    ) {
        // Buffer must hold at least two frames so AudioRecord never blocks waiting for space.
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(FRAME_SAMPLES * Short.SIZE_BYTES * 2)

        val audioRecord = try { AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufSize,
        )
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordDetector: AudioRecord construction failed", e)
            running.set(false)
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "WakeWordDetector: AudioRecord failed to initialise")
            running.set(false)
            return
        }
        activeAudioRecord = audioRecord


        // ── Closeable ORT resources (nullable vars so finally covers all paths) ──
        var cpuOptions: OrtSession.SessionOptions? = null
        var embedOptions: OrtSession.SessionOptions? = null
        var melsSession: OrtSession? = null
        var embedSession: OrtSession? = null
        var classSession: OrtSession? = null
        val diagnosticsEnabled = Log.isLoggable(TAG, Log.DEBUG)
        val diagnostics = if (diagnosticsEnabled) WakeWordDiagnosticCounters() else null
        val diagnosticsStartedAt = if (diagnosticsEnabled) SystemClock.elapsedRealtime() else 0L
        var lastDiagnosticReportElapsedMillis = 0L
        var nnapiStatus = "not_requested"

        try {
            val env = OrtEnvironment.getEnvironment()
            cpuOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }
            embedOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }
            val nnapiConfigured = runCatching {
                embedOptions!!.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            }.onFailure {
                Log.w(TAG, "WakeWordDetector: NNAPI EP configuration failed; embedding session will use CPU", it)
            }.isSuccess
            nnapiStatus = if (nnapiConfigured) {
                "requested_cpu_disabled"
            } else {
                "configuration_failed_cpu_session"
            }

            melsSession = runCatching { env.createSession(bytes.melspectrogram, cpuOptions!!) }
                .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load melspectrogram.onnx", e); null }
            embedSession = runCatching { env.createSession(bytes.embedding, embedOptions!!) }
                .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load embedding_model.onnx", e); null }
            classSession = runCatching { env.createSession(bytes.classifier, cpuOptions!!) }
                .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load hey_jandal.onnx", e); null }
            nnapiStatus = when {
                embedSession == null && nnapiConfigured -> "session_create_failed_after_nnapi_request"
                embedSession == null -> "cpu_session_create_failed"
                nnapiConfigured -> "session_created_nnapi_requested_assignment_unverified"
                else -> "cpu_session_created"
            }

            if (melsSession == null || embedSession == null || classSession == null) {
                Log.e(TAG, "WakeWordDetector: one or more ONNX sessions failed to load")
                return

            }
            Log.i(TAG, "WakeWordDetector: models loaded (embedding provider=$nnapiStatus; mel+classifier: CPU)")


            // Resolve ONNX node names once at startup.
            val melsInputName   = melsSession.inputNames.first()
            val melsOutputName  = melsSession.outputNames.first()
            val embedInputName  = embedSession.inputNames.first()
            val embedOutputName = embedSession.outputNames.first()
            val classInputName  = classSession.inputNames.first()
            val classOutputName = classSession.outputNames.first()

            // Pre-allocate all hot-loop buffers — zero heap churn during detection.
            val melRing = FloatArray(MEL_RING_SIZE * MEL_BINS)
            var melRowsFilled = 0
            var chunkCount = 0

            val embeddingRing = Array(EMBEDDING_FRAMES) { FloatArray(EMBEDDING_DIM) }
            var embRingHead = 0
            var embFramesAccumulated = 0
            val chunk    = ShortArray(FRAME_SAMPLES)
            val framePcm = FloatArray(FRAME_SAMPLES)
            val windowFlat = FloatArray(EMBEDDING_FRAMES * EMBEDDING_DIM)
            val embedInput4D = FloatArray(MEL_RING_SIZE * MEL_BINS)

            val pcmRing     = if (verifyWindow != null) ShortArray(VERIFY_RING_SAMPLES) else ShortArray(0)
            var pcmRingHead = 0
            var pcmFilled   = 0
            var silenceFrames = 0
            var voicedFrameStreak = 0
            var wasGated = false
            runCatching { audioRecord.startRecording() }
                .onFailure { e -> Log.e(TAG, "WakeWordDetector: startRecording failed", e); running.set(false); return }
            Log.d(TAG, "WakeWordDetector: recording started")
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // Read exactly one 80ms frame; abort if AudioRecord signals an error.
                var totalRead = 0
                while (totalRead < FRAME_SAMPLES && running.get()) {
                    val read = try {
                        audioRecord.read(chunk, totalRead, FRAME_SAMPLES - totalRead)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "WakeWordDetector: permission revoked during read", e)
                        running.set(false)
                        -1
                    }
                    when {
                        read > 0 -> totalRead += read
                        read < 0 -> {
                            Log.e(TAG, "WakeWordDetector: AudioRecord.read() error $read — stopping")
                            running.set(false)
                        }
                        // read == 0: no data yet, spin
                    }
                }
                if (totalRead < FRAME_SAMPLES) continue
                diagnostics?.recordAudioFrame()
                chunkCount++
                val rms = calculateRms(chunk)



                // Feed the current frame into the PCM ring buffer.
                if (verifyWindow != null) {
                    val spaceToEnd = VERIFY_RING_SAMPLES - pcmRingHead
                    if (FRAME_SAMPLES <= spaceToEnd) {
                        chunk.copyInto(pcmRing, pcmRingHead, 0, FRAME_SAMPLES)
                        pcmRingHead = (pcmRingHead + FRAME_SAMPLES) % VERIFY_RING_SAMPLES
                    } else {
                        chunk.copyInto(pcmRing, pcmRingHead, 0, spaceToEnd)
                        chunk.copyInto(pcmRing, 0, spaceToEnd, FRAME_SAMPLES)
                        pcmRingHead = FRAME_SAMPLES - spaceToEnd
                    }
                    if (pcmFilled < VERIFY_RING_SAMPLES) pcmFilled += FRAME_SAMPLES
                }


                // ── Fast-open / slow-close voice detection ───────────────────────
                // Fast-open: a single frame above threshold immediately resets the
                // silence counter, un-gating Stage 2/3 so the classifier sees speech
                // onset within 80ms instead of 240ms.
                // Slow-close: require 3 consecutive silent frames before the silence
                // timer starts accumulating, so a single 80ms transient (door, tap,
                // car passing) doesn't falsely re-enter gated mode.
                val isFrameVoiced = rms >= silenceRmsThreshold
                if (isFrameVoiced) {
                    // Flush the embedding ring on speech onset after gated silence.
                    // The ring holds stale silence embeddings from before gating.
                    // Resetting embFramesAccumulated forces a clean 16-frame refill
                    // from live audio.  The mel ring slides naturally (Stage 1 runs
                    // every frame even during gating), so the first speech frame
                    // already pre-fills it with speech mel data.
                    // Latency: 80ms + 16-frame ring fill (~1.3s) ≈ ~1.4s.
                    if (wasGated) {
                        embFramesAccumulated = 0
                        wasGated = false
                    }
                    silenceFrames = 0
                    voicedFrameStreak = 0
                } else {
                    voicedFrameStreak++
                    if (voicedFrameStreak >= 3) {
                        silenceFrames++
                    }
                }
                // ── Stage 1: mel spectrogram (runs on every frame) ──────────────────
                // Keeps the mel ring fresh during gated silence so that when speech
                // resumes, only the 16-frame embedding ring needs to refill (~1.3s).
                // openWakeWord's mel model expects raw 16-bit PCM values cast to float32
                // (range ±32768), NOT normalised to [-1, 1]. Using the wrong scale shifts
                // the mel output by ~88 units, putting embeddings completely out of the
                // distribution the classifier was trained on (verified empirically).
                for (i in 0 until FRAME_SAMPLES) {
                    framePcm[i] = chunk[i].toFloat()
                }
                // Input:  [1, 1280] float32 PCM
                // Output: [1, 1, 5, 32] mel spectrogram patch (5 rows × 32 bins per chunk)
                diagnostics?.recordStage1Execution()
                val melRows: FloatArray = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(framePcm),
                    longArrayOf(1L, FRAME_SAMPLES.toLong()),
                ).use { melIn ->
                    melsSession.run(mapOf(melsInputName to melIn)).use { melOut ->
                        val t = melOut[melsOutputName].get() as OnnxTensor
                        val rows = (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)
                        val flat = FloatArray(MEL_ROWS_PER_CHUNK * MEL_BINS)
                        for (r in 0 until MEL_ROWS_PER_CHUNK) {
                            val row = rows[r] as FloatArray
                            val base = r * MEL_BINS
                            for (b in 0 until MEL_BINS) {
                                flat[base + b] = row[b] / 10.0f + 2.0f
                            }
                        }
                        flat
                    }
                }

                // Slide mel ring: drop oldest MEL_ROWS_PER_CHUNK rows, append new rows.
                if (melRowsFilled >= MEL_RING_SIZE) {
                    melRing.copyInto(melRing, 0, MEL_ROWS_PER_CHUNK * MEL_BINS, MEL_RING_SIZE * MEL_BINS)
                    melRows.copyInto(melRing, (MEL_RING_SIZE - MEL_ROWS_PER_CHUNK) * MEL_BINS)
                } else {
                    val rowsToInsert = minOf(MEL_ROWS_PER_CHUNK, MEL_RING_SIZE - melRowsFilled)
                    melRows.copyInto(melRing, melRowsFilled * MEL_BINS, 0, rowsToInsert * MEL_BINS)
                    melRowsFilled += rowsToInsert
                }
                if (melRowsFilled < MEL_RING_SIZE) continue

                if (diagnostics != null && chunkCount % DIAGNOSTIC_REPORT_CHECK_FRAMES == 0L) {
                    val elapsedMillis = SystemClock.elapsedRealtime() - diagnosticsStartedAt
                    if (elapsedMillis - lastDiagnosticReportElapsedMillis >= DIAGNOSTIC_REPORT_INTERVAL_MILLIS) {
                        Log.d(TAG, formatDiagnosticSummary(diagnostics.snapshot(elapsedMillis), nnapiStatus))
                        lastDiagnosticReportElapsedMillis = elapsedMillis
                    }
                }

                // ── Gating: skip embedding + classifier when confirmed-silent ─────
                if (silenceFrames > silenceHangoverFrames &&
                    chunkCount % maxSilenceSkipFrames.toLong() != 0L) {
                    diagnostics?.recordSilenceGateSkip()
                    wasGated = true
                    continue  // wake word not expected — skip expensive Stage 2/3
                }

                // ── Stage 2: embedding backbone ───────────────────────────────────────
                // Input:  [1, 76, 32, 1] — melRing reshaped; the model expects a channel dim of 1.
                // Output: [1, 1, 1, 96] — one 96-dim embedding vector.
                //
                // melRing is already [76 × 32] row-major.  We reinterpret as [76 × 32 × 1] by
                // passing the same flat array with shape [1, 76, 32, 1] — the channel is implicit
                // (every element maps to exactly one channel-1 position).
                melRing.copyInto(embedInput4D)
                diagnostics?.recordStage2Execution()
                val embedding: FloatArray = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(embedInput4D),
                    longArrayOf(1L, MEL_RING_SIZE.toLong(), MEL_BINS.toLong(), 1L),
                ).use { embedIn ->
                    embedSession.run(mapOf(embedInputName to embedIn)).use { embedOut ->
                        val t = embedOut[embedOutputName].get() as OnnxTensor
                        // Output shape [1,1,1,96] — innermost array is FloatArray(96).
                        (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)[0] as FloatArray
                    }
                }

                // Accumulate embedding in ring buffer.
                embedding.copyInto(embeddingRing[embRingHead], 0, 0, EMBEDDING_DIM)
                embRingHead = (embRingHead + 1) % EMBEDDING_FRAMES
                if (embFramesAccumulated < EMBEDDING_FRAMES) embFramesAccumulated++
                if (embFramesAccumulated < EMBEDDING_FRAMES) continue

                // ── Stage 3: classifier over the last 16 embedding frames ─────────────
                // Input:  [1, 16, 96]
                // Output: [1, 1]
                for (f in 0 until EMBEDDING_FRAMES) {
                    val frameIdx = (embRingHead + f) % EMBEDDING_FRAMES
                    embeddingRing[frameIdx].copyInto(windowFlat, f * EMBEDDING_DIM)
                }

                diagnostics?.recordStage3Execution()
                val confidence: Float = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(windowFlat),
                    longArrayOf(1L, EMBEDDING_FRAMES.toLong(), EMBEDDING_DIM.toLong()),
                ).use { classIn ->
                    classSession.run(mapOf(classInputName to classIn)).use { classOut ->
                        val t = classOut[classOutputName].get() as OnnxTensor
                        ((t.value as Array<*>)[0] as FloatArray)[0]
                    }
                }
                // Stage 3 count is retained in low-frequency diagnostics; avoid per-second logs.


                when {
                    // High-confidence fast path — activate immediately.
                    confidence >= highThreshold -> {
                        Log.i(TAG, "WakeWordDetector: detected (high confidence=$confidence)")
                        if (running.compareAndSet(true, false)) {
                            diagnostics?.recordHighConfidenceActivation()
                            onDetected()
                        }
                    }

                    // Secondary band — run STT verification if a verifier is wired.
                    verifyWindow != null && confidence >= lowThreshold -> {
                        Log.d(TAG, "WakeWordDetector: low-threshold crossing (confidence=$confidence) — verifying")
                        val snapshot = extractPcmSnapshot(pcmRing, pcmRingHead, pcmFilled)
                        val verified = verifyWindow(snapshot)
                        diagnostics?.recordVerifierResult(verified)
                        if (verified) {
                            Log.i(TAG, "WakeWordDetector: STT verification passed — activating")
                            if (running.compareAndSet(true, false)) {
                                diagnostics?.recordVerifiedActivation()
                                onDetected()
                            }
                        } else {
                            Log.d(TAG, "WakeWordDetector: STT verification rejected — continuing")
                        }
                    }

                    else -> Unit
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordDetector: fatal error", e)
        } finally {
            activeAudioRecord = null
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            melsSession?.close()
            embedSession?.close()
            classSession?.close()
            cpuOptions?.close()
            embedOptions?.close()
            running.set(false)
            diagnostics?.let {
                val elapsedMillis = SystemClock.elapsedRealtime() - diagnosticsStartedAt
                Log.d(TAG, formatDiagnosticSummary(it.snapshot(elapsedMillis), nnapiStatus, final = true))
            }
        }
    }

    private fun formatDiagnosticSummary(
        snapshot: WakeWordDiagnosticSnapshot,
        nnapiStatus: String,
        final: Boolean = false,
    ): String = buildString {
        append("WakeWordDetector: diagnostics")
        if (final) append(" final")
        append(" elapsedMs=").append(snapshot.elapsedMillis)
        append(" audioFrames=").append(snapshot.audioFrames)
        append(" stage1=").append(snapshot.stage1Executions)
        append(" stage2=").append(snapshot.stage2Executions)
        append(" stage3=").append(snapshot.stage3Executions)
        append(" stage2PerHour=").append(snapshot.stage2ExecutionsPerHour())
        append(" stage3PerHour=").append(snapshot.stage3ExecutionsPerHour())
        append(" silenceSkips=").append(snapshot.silenceGateSkips)
        append(" silenceSkipRatio=").append(snapshot.silenceGateSkipRatio)
        append(" verifier=").append(snapshot.verifierInvocations)
        append(" verifierPasses=").append(snapshot.verifierPasses)
        append(" verifierRejects=").append(snapshot.verifierRejects)
        append(" highActivations=").append(snapshot.highConfidenceActivations)
        append(" verifiedActivations=").append(snapshot.verifiedActivations)
        append(" embeddingProvider=").append(nnapiStatus)
    }

    /**
     * Copies the PCM ring buffer contents into a chronologically ordered [ShortArray].
     *
     * Only the filled portion is returned (up to [VERIFY_RING_SAMPLES] samples).
     * The result is a standalone copy — safe to hand off to [verifyWindow] while the
     * detection loop continues filling the ring.
     */
    private fun extractPcmSnapshot(ring: ShortArray, head: Int, filled: Int): ShortArray {
        if (filled == 0) return ShortArray(0)
        val size = filled.coerceAtMost(VERIFY_RING_SAMPLES)
        val out  = ShortArray(size)
        if (filled < VERIFY_RING_SAMPLES) {
            // Buffer not yet full: data is contiguous from index 0.
            ring.copyInto(out, 0, 0, size)
        } else {
            // Buffer full: oldest sample is at `head`, newest is at `head - 1` (mod size).
            val tailLen = VERIFY_RING_SAMPLES - head
            ring.copyInto(out, 0, head, VERIFY_RING_SAMPLES)
            ring.copyInto(out, tailLen, 0, head)
        }
        return out
    }
}

internal fun calculateRms(chunk: ShortArray): Double {
    var sum = 0.0
    for (sample in chunk) {
        val value = sample.toDouble()
        sum += value * value
    }
    return Math.sqrt(sum / FRAME_SAMPLES)
}

internal fun secondsToFrames(seconds: Float): Int =
    kotlin.math.ceil(seconds / WAKE_WORD_FRAME_DURATION_SECONDS).toInt().coerceAtLeast(1)
