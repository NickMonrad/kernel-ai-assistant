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
private const val DIAGNOSTIC_TAG = "WakeWordDiag"

internal fun isWakeWordDiagnosticLoggingEnabled(): Boolean =
    Log.isLoggable(DIAGNOSTIC_TAG, Log.DEBUG)

/**
 * Transition state for silence-gate journal events.
 *
 * Periodic full-inference frames do not leave the gate. Only a voiced frame does,
 * and the first Stage 2 execution after that voiced exit emits the resume event.
 */
internal class SilenceGateTransitionState {
    var isGated: Boolean = false
        private set

    private var stage2ResumePending = false

    fun enter(): Boolean {
        if (isGated) return false
        isGated = true
        return true
    }

    fun onVoicedFrame(): Boolean {
        if (!isGated) return false
        isGated = false
        stage2ResumePending = true
        return true
    }

    fun onStage2Execution(): Boolean {
        if (!stage2ResumePending) return false
        stage2ResumePending = false
        return true
    }
}

/**
 * Chronological embedding ring feeding the Stage 3 classifier window (#1432).
 *
 * One 96-dim embedding is appended per 80 ms inference frame; the classifier
 * input is exactly the last [capacity] embeddings in chronological order
 * ([copyWindow]), and scoring is only permitted once the window is complete
 * ([isWindowComplete]).
 *
 * The ring is allocated per detector generation, so it can never contain
 * embeddings from an earlier generation; [reset] models the generation /
 * re-arm boundary used by the detector (fresh allocation per
 * [OnnxWakeWordDetector.runDetectionLoop]).
 *
 * **Silence-gate resume semantics:** the ring is intentionally NOT flushed on
 * gate exit.  A complete ring holds 16 chronological, same-generation
 * embeddings (periodic silence probes + pre-onset audio) that are valid
 * classifier context.  The #1410 physical evidence shows the classifier scores
 * the wake phrase only while the phrase sits at the tail of the embedding
 * window (passing trials on both devices fire at phrase onset + ~0.9 s, before
 * the phrase is fully inside the receptive field).  The previous unconditional
 * 16-frame refill after gate exit therefore skipped every scoreable window and
 * intermittently lost ACTIVATION_CANDIDATE (11 S21 + 6 S23U classified misses).
 * Stage 3 still refuses to score an incomplete window, so initial startup and
 * genuinely incomplete rings keep the existing wait-for-refill guarantee.
 */
internal class WakeWordEmbeddingRingState(
    val capacity: Int = EMBEDDING_FRAMES,
    val dim: Int = EMBEDDING_DIM,
) {
    private val ring = Array(capacity) { FloatArray(dim) }

    /** Ring head: slot the next embedding will overwrite. */
    var head: Int = 0
        private set

    /** Number of valid embeddings appended by the current generation. */
    var accumulated: Int = 0
        private set

    /** True once [capacity] embeddings have been appended (window scoreable). */
    val isWindowComplete: Boolean get() = accumulated >= capacity

    /** Clears all state at a detector generation / re-arm boundary. */
    fun reset() {
        head = 0
        accumulated = 0
    }

    /** Appends one embedding and advances the ring head. */
    fun append(embedding: FloatArray) {
        embedding.copyInto(ring[head], 0, 0, dim)
        head = (head + 1) % capacity
        if (accumulated < capacity) accumulated++
    }

    /**
     * Copies the classifier window — the last [capacity] embeddings in
     * chronological order — into [out] (length capacity * dim).
     * Only meaningful when [isWindowComplete].
     */
    fun copyWindow(out: FloatArray) {
        for (f in 0 until capacity) {
            ring[(head + f) % capacity].copyInto(out, f * dim)
        }
    }
}

/**
 * Diagnostic summaries are only emitted when the dedicated tag's DEBUG level is enabled.
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
internal const val FRAME_SAMPLES = WAKE_WORD_FRAME_SAMPLES

/**
 * Number of raw samples of the previous chunk that the openWakeWord streaming
 * mel path re-uses as lead-in context: the reference always computes mel over
 * the last `n + 160*3` buffered samples (current chunk + previous 480).
 */
internal const val MEL_LEAD_SAMPLES = 480

/**
 * Number of mel-spectrogram rows produced by melspectrogram.onnx for the
 * openWakeWord streaming window (current 1280-sample chunk plus the previous
 * 480 samples).  Empirically verified: input [1, 1760] → output [1, 1, 8, 32],
 * so 8 rows per chunk (the first chunk, with only 1280 samples buffered,
 * yields 5 rows).  The mel model has a dynamic sample axis; feeding exactly
 * 1280 samples would return only the 5 rows over the current chunk and skip
 * the 3 boundary rows the reference pipeline computes over the previous
 * chunk's tail (#1432 parity).
 */
internal const val MEL_ROWS_PER_CHUNK = 8

/** Number of mel frequency bins — mel model output dim 3. */
internal const val MEL_BINS = 32

/**
 * The embedding backbone (embedding_model.onnx) expects input shape [batch, 76, 32, 1].
 * 76 mel rows = 9.5 chunks = ~1,216ms of audio history (8 rows per chunk).
 */
internal const val MEL_RING_SIZE = 76

// ── Embedding ring buffer ─────────────────────────────────────────────────────
/**
 * Number of embedding frames fed to the classifier at each step.
 *
 * hey_jandal.onnx input shape: [1, 16, 96] — confirmed from model introspection.
 * Each embedding covers ~1.2s of audio (76 mel rows × 80ms / 8 rows per chunk).
 * 16 overlapping frames yield ~2.4s of effective receptive field, not 19.5s.
 */
internal const val EMBEDDING_FRAMES = 16
internal const val EMBEDDING_DIM = 96

// ── Extracted production feature-pipeline steps ──────────────────────────────
// These functions ARE the production Stage 1/2/3 tensor logic (the detector hot
// loop calls them; the #1432 parity test drives the same functions on fixed
// PCM).  They must stay behaviour-identical to the detector loop — any change
// here changes on-device detection.

/**
 * Runs the production Stage 1 mel frontend on [pcm] (raw 16-bit PCM values
 * cast to float32, ±32768 scale — NOT normalised to [-1, 1]; the openWakeWord
 * mel model is trained on the raw scale) and applies the openWakeWord
 * transform (value/10 + 2) to every output row.
 *
 * Returns the first [maxRows] output rows flattened to [rows*32] float32.
 * melspectrogram.onnx has a dynamic sample axis: a 1280-sample input yields
 * 5 rows and a 1760-sample input yields 8 rows (verified empirically;
 * mel(1760)[3:8] == mel(1280)[0:5] bit-exactly).
 */
internal fun computeMelRows(
    env: OrtEnvironment,
    melsSession: OrtSession,
    melsInputName: String,
    melsOutputName: String,
    pcm: FloatArray,
    samples: Int,
    maxRows: Int,
): FloatArray {
    val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm, 0, samples), longArrayOf(1L, samples.toLong()))
    return tensor.use { melIn ->
        melsSession.run(mapOf(melsInputName to melIn)).use { melOut ->
            val t = melOut[melsOutputName].get() as OnnxTensor
            val rows = (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)
            val rowCount = minOf(maxRows, rows.size)
            val flat = FloatArray(rowCount * MEL_BINS)
            for (r in 0 until rowCount) {
                val row = rows[r] as FloatArray
                val base = r * MEL_BINS
                for (b in 0 until MEL_BINS) {
                    flat[base + b] = row[b] / 10.0f + 2.0f
                }
            }
            flat
        }
    }
}

/**
 * Slides the production 76×32 mel ring: appends [melRows] ([rowsPerChunk]*32
 * values) and drops the oldest rows once the ring is full.  The drop count is
 * the exact overflow (filled + appended − 76), so the ring always holds the
 * last 76 rows of the mel-row stream — a capacity-76 sliding window, exactly
 * the window the openWakeWord streaming reference presents to the embedding
 * model at every chunk (#1432 parity).  Returns the new filled count.
 */
internal fun appendMelRows(
    melRing: FloatArray,
    melRowsFilled: Int,
    melRows: FloatArray,
    rowsPerChunk: Int,
): Int {
    val rowsToAppend = melRows.size / MEL_BINS
    val overflow = melRowsFilled + rowsToAppend - MEL_RING_SIZE
    if (overflow > 0) {
        melRing.copyInto(melRing, 0, overflow * MEL_BINS, melRowsFilled * MEL_BINS)
        melRows.copyInto(melRing, (melRowsFilled - overflow) * MEL_BINS)
        return MEL_RING_SIZE
    }
    val rowsToInsert = minOf(rowsToAppend, MEL_RING_SIZE - melRowsFilled)
    melRows.copyInto(melRing, melRowsFilled * MEL_BINS, 0, rowsToInsert * MEL_BINS)
    return melRowsFilled + rowsToInsert
}

/**
 * Production Stage-1 mel feature state: the 76×32 mel ring plus the
 * 480-sample lead-in tail retained from the previous chunk.
 *
 * [stage1] reproduces the openWakeWord streaming mel framing exactly (#1432
 * parity): the mel model is fed the last `n + 480` buffered samples — the
 * current chunk plus the tail of the previous chunk — which yields 5 rows for
 * the very first chunk (only 1280 samples buffered) and 8 rows per chunk
 * afterwards.  All rows are appended to the ring; [filled] reaches 76 at
 * chunk 10, so the first embedding is computed at the same chunk and from the
 * same 76-row window as the Python reference.
 *
 * [input] is a caller-owned scratch buffer of at least
 * [FRAME_SAMPLES] + [MEL_LEAD_SAMPLES] floats (preallocated by the detector
 * loop to keep the hot path allocation-free).
 */
internal class WakeWordMelFeatureState {
    private val ring = FloatArray(MEL_RING_SIZE * MEL_BINS)
    private val tail = FloatArray(MEL_LEAD_SAMPLES)
    private var tailFilled = 0

    /** Number of valid mel rows in the ring (76 once Stage 2 can run). */
    var filled = 0
        private set

    /** Samples fed to the mel model by the last [stage1] call (1280 or 1760). */
    var lastInputSamples = 0
        private set

    /** Mel rows appended by the last [stage1] call (5 or 8). */
    var lastRowsAppended = 0
        private set

    /** Clears ring and tail at a detector generation / re-arm boundary. */
    fun reset() {
        ring.fill(0f)
        tail.fill(0f)
        tailFilled = 0
        filled = 0
        lastInputSamples = 0
        lastRowsAppended = 0
    }

    /**
     * Runs the production Stage 1 on [framePcm] (one 1280-sample chunk, raw
     * int16-scale float32) and appends the resulting mel rows to the ring.
     * Returns the appended rows flattened ([lastRowsAppended]*32 floats).
     */
    fun stage1(
        env: OrtEnvironment,
        melsSession: OrtSession,
        melsInputName: String,
        melsOutputName: String,
        framePcm: FloatArray,
        input: FloatArray,
    ): FloatArray {
        val inputSamples: Int
        if (tailFilled >= MEL_LEAD_SAMPLES) {
            tail.copyInto(input, 0)
            framePcm.copyInto(input, MEL_LEAD_SAMPLES)
            inputSamples = FRAME_SAMPLES + MEL_LEAD_SAMPLES
        } else {
            framePcm.copyInto(input, 0, 0, FRAME_SAMPLES)
            inputSamples = FRAME_SAMPLES
        }
        lastInputSamples = inputSamples
        val melRows = computeMelRows(
            env, melsSession, melsInputName, melsOutputName, input, inputSamples, MEL_ROWS_PER_CHUNK,
        )
        framePcm.copyInto(tail, 0, FRAME_SAMPLES - MEL_LEAD_SAMPLES, FRAME_SAMPLES)
        tailFilled = MEL_LEAD_SAMPLES
        lastRowsAppended = melRows.size / MEL_BINS
        filled = appendMelRows(ring, filled, melRows, lastRowsAppended)
        return melRows
    }

    /** Copies the current 76×32 ring into [out] for the Stage 2 input. */
    fun copyStage2Input(out: FloatArray) {
        ring.copyInto(out)
    }
}

/**
 * Runs the production Stage 2 embedding backbone on the 76×32 [melRing]:
 * input [1, 76, 32, 1] (row-major ring reinterpreted with the channel dim
 * implicit), output [1, 1, 1, 96] → one 96-dim embedding vector.
 */
internal fun computeEmbedding(
    env: OrtEnvironment,
    embedSession: OrtSession,
    embedInputName: String,
    embedOutputName: String,
    melRing: FloatArray,
): FloatArray {
    val tensor = OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(melRing),
        longArrayOf(1L, MEL_RING_SIZE.toLong(), MEL_BINS.toLong(), 1L),
    )
    return tensor.use { embedIn ->
        embedSession.run(mapOf(embedInputName to embedIn)).use { embedOut ->
            val t = embedOut[embedOutputName].get() as OnnxTensor
            // Output shape [1,1,1,96] — innermost array is FloatArray(96).
            (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)[0] as FloatArray
        }
    }
}

/**
 * Runs the production Stage 3 classifier on the flattened 16×96 [windowFlat]
 * embedding window: input [1, 16, 96], output [1, 1] → confidence in [0, 1].
 */
internal fun computeClassifierConfidence(
    env: OrtEnvironment,
    classSession: OrtSession,
    classInputName: String,
    classOutputName: String,
    windowFlat: FloatArray,
): Float {
    val tensor = OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(windowFlat),
        longArrayOf(1L, EMBEDDING_FRAMES.toLong(), EMBEDDING_DIM.toLong()),
    )
    return tensor.use { classIn ->
        classSession.run(mapOf(classInputName to classIn)).use { classOut ->
            val t = classOut[classOutputName].get() as OnnxTensor
            ((t.value as Array<*>)[0] as FloatArray)[0]
        }
    }
}

/**
 * Upper bound for [OnnxWakeWordDetector.stop]'s join on the detection thread.
 * The loop exits immediately after a wake callback (running is already cleared),
 * so this is only a safety bound for a pathological in-flight read.
 */
private const val STOP_TEARDOWN_TIMEOUT_MS = 2_000L

/**
 * Wait (bounded) for [thread] to terminate.  Never joins the caller's own thread,
 * so this is safe even when invoked from the thread being stopped.  Interruptions
 * of the caller are preserved rather than swallowed.
 */
internal fun awaitThreadTermination(thread: Thread?, timeoutMs: Long) {
    if (thread != null && thread !== Thread.currentThread() && thread.isAlive) {
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

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
  *   → 80ms chunks (1280 samples) → Stage 1: mel spectrogram (5 rows for the first chunk, 8 rows per chunk over the 1760-sample streaming window)
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
  * - Input:  `float32[1, 1760]` — one 80ms frame plus the previous 480-sample
  *   tail (openWakeWord streaming window), raw 16-bit PCM values as float32
  *   (±32768, not [-1, 1]); the very first chunk is `[1, 1280]`
  * - Output: `float32[1, 1, 8, 32]` — 8 mel rows × 32 bins per 80ms chunk
  *   (5 rows for the first chunk)
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
    override fun start(
        generationId: Long,
        onDetected: () -> Unit,
        verifyWindow: ((ShortArray) -> Boolean)?,
    ) {
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
                    generationId = generationId,
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
        val thread = detectionThread
        runCatching { thread?.interrupt() }
        detectionThread = null
        // #1433: return only after the detection loop has terminated and released its
        // AudioRecord, so callers know microphone ownership is handed over when stop()
        // completes.  The loop exits promptly: onDetected runs after `running` has
        // already been cleared and the next loop iteration checks it, and an
        // in-progress read is unblocked by the AudioRecord.stop() above.  The join is
        // bounded and never joins the caller's own thread, so stop() cannot deadlock
        // even if it were invoked from the detection thread itself.
        awaitThreadTermination(thread, STOP_TEARDOWN_TIMEOUT_MS)
        Log.d(TAG, "WakeWordDetector: stopped")
    }
    @Suppress("LongMethod")
    @SuppressLint("MissingPermission")
    private fun runDetectionLoop(
        generationId: Long,
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
            recordDetectorError(generationId, "audio_record_construction_failed")
            running.set(false)
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "WakeWordDetector: AudioRecord failed to initialise")
            recordDetectorError(generationId, "audio_record_initialisation_failed")
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
        val diagnosticsEnabled = isWakeWordDiagnosticLoggingEnabled()
        val diagnostics = if (diagnosticsEnabled) WakeWordDiagnosticCounters() else null
        val diagnosticsStartedAt = if (diagnosticsEnabled) SystemClock.elapsedRealtime() else 0L
        var lastDiagnosticReportElapsedMillis = 0L
        var nnapiStatus = "not_requested"

        // ── Target event journal tracking ──────────────────────────────────────
        val silenceGateState = SilenceGateTransitionState()
        var emittedStage3Ready = false
        // Per-gate-exit confidence summary (#1432 bounded reproduction).
        // Debug-gated: only instantiated when the WakeWordDiag tag is
        // DEBUG-enabled; zero allocations or branches otherwise.
        val gateExitDiag = if (diagnosticsEnabled) WakeWordGateExitDiagnostics(generationId) else null

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
                recordDetectorError(generationId, "onnx_session_failed")
                return
            }
            Log.i(TAG, "WakeWordDetector: models loaded (embedding provider=$nnapiStatus; mel+classifier: CPU)")

            AcousticJournalBridge.record(
                type = AcousticEventType.DETECTOR_GENERATION_STARTED,
                generationId = generationId,
            )


            // Resolve ONNX node names once at startup.
            val melsInputName   = melsSession.inputNames.first()
            val melsOutputName  = melsSession.outputNames.first()
            val embedInputName  = embedSession.inputNames.first()
            val embedOutputName = embedSession.outputNames.first()
            val classInputName  = classSession.inputNames.first()
            val classOutputName = classSession.outputNames.first()

            // Pre-allocate all hot-loop buffers — zero heap churn during detection.
            val melState = WakeWordMelFeatureState()
            val melInputPcm = FloatArray(FRAME_SAMPLES + MEL_LEAD_SAMPLES)
            var chunkCount = 0

            val embeddingRing = WakeWordEmbeddingRingState()
            val chunk    = ShortArray(FRAME_SAMPLES)
            val framePcm = FloatArray(FRAME_SAMPLES)
            val windowFlat = FloatArray(EMBEDDING_FRAMES * EMBEDDING_DIM)
            val embedInput4D = FloatArray(MEL_RING_SIZE * MEL_BINS)

            val pcmRing     = if (verifyWindow != null) ShortArray(VERIFY_RING_SAMPLES) else ShortArray(0)
            var pcmRingHead = 0
            var pcmFilled   = 0
            var silenceFrames = 0
            var voicedFrameStreak = 0
            runCatching { audioRecord.startRecording() }
                .onFailure { e ->
                    Log.e(TAG, "WakeWordDetector: startRecording failed", e)
                    recordDetectorError(generationId, "audio_record_start_failed")
                    running.set(false)
                    return
                }
            Log.d(TAG, "WakeWordDetector: recording started")
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // Read exactly one 80ms frame; abort if AudioRecord signals an error.
                var totalRead = 0
                while (totalRead < FRAME_SAMPLES && running.get()) {
                    val read = try {
                        audioRecord.read(chunk, totalRead, FRAME_SAMPLES - totalRead)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "WakeWordDetector: permission revoked during read", e)
                        recordDetectorError(generationId, "audio_record_permission_revoked")
                        running.set(false)
                        -1
                    }
                    when {
                        read > 0 -> totalRead += read
                        read < 0 -> {
                            Log.e(TAG, "WakeWordDetector: AudioRecord.read() error $read — stopping")
                            recordDetectorError(generationId, "audio_record_read_failed")
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
                    // #1432: preserve the embedding ring across silence-gate exit.
                    // The ring holds 16 chronological, same-generation embeddings
                    // (periodic silence probes + pre-onset audio) that are valid
                    // classifier context.  The previous unconditional
                    // embFramesAccumulated reset forced a 16-frame (~1.28 s)
                    // classifier blackout after gate exit; the #1410 evidence shows
                    // the classifier scores the wake phrase only while it sits at
                    // the tail of the embedding window (pass trials fire at
                    // phrase onset + ~0.9 s), so the blackout skipped every
                    // scoreable window and intermittently lost ACTIVATION_CANDIDATE.
                    // Stage 3 still refuses to score an incomplete window (see the
                    // isWindowComplete guard below), so startup and genuinely
                    // incomplete rings keep the wait-for-refill guarantee.
                    if (silenceGateState.onVoicedFrame()) {
                        emittedStage3Ready = false
                        AcousticJournalBridge.record(
                            type = AcousticEventType.VOICED_FRAME_AFTER_SILENCE,
                            generationId = generationId,
                        )
                        gateExitDiag?.onGateExited(chunkCount)
                    }
                    silenceFrames = 0
                    voicedFrameStreak = 0
                } else {
                    voicedFrameStreak++
                    if (voicedFrameStreak >= 3) {
                        silenceFrames++
                    }
                }
                // Episode audio energy: recorded after the silence-gate transition
                // for this frame, so the voiced frame that triggered
                // onGateExited() is included in episodePeakRms and gated pre-exit
                // frames are excluded (debug-gated; no-op when disabled).
                gateExitDiag?.onEpisodeFrameRms(rms.toFloat())
                // ── Stage 1: mel spectrogram (runs on every frame) ──────────────────
                // Keeps the mel ring fresh during gated silence so that when speech
                // resumes, the embedding ring already carries valid context and Stage 3
                // can score the wake phrase while it is still at the tail of the
                // window (see #1432 — no post-gate refill blackout).
                // openWakeWord's mel model expects raw 16-bit PCM values cast to float32
                // (range ±32768), NOT normalised to [-1, 1]. Using the wrong scale shifts
                // the mel output by ~88 units, putting embeddings completely out of the
                // distribution the classifier was trained on (verified empirically).
                for (i in 0 until FRAME_SAMPLES) {
                    framePcm[i] = chunk[i].toFloat()
                }
                // Input:  the openWakeWord streaming window — the current
                // 1280-sample chunk plus the previous chunk's 480-sample tail
                // ([1, 1760] → [1, 1, 8, 32]; the first chunk is [1, 1280] →
                // [1, 1, 5, 32]).  Feeding only the 1280-sample chunk would
                // compute 5 rows and never the 3 boundary rows over the
                // previous tail, diverging from the training/reference mel
                // ring (#1432 parity).
                diagnostics?.recordStage1Execution()
                melState.stage1(env, melsSession, melsInputName, melsOutputName, framePcm, melInputPcm)
                if (melState.filled < MEL_RING_SIZE) continue

                if (diagnostics != null && chunkCount % DIAGNOSTIC_REPORT_CHECK_FRAMES == 0L) {
                    val elapsedMillis = SystemClock.elapsedRealtime() - diagnosticsStartedAt
                    if (elapsedMillis - lastDiagnosticReportElapsedMillis >= DIAGNOSTIC_REPORT_INTERVAL_MILLIS) {
                        Log.d(DIAGNOSTIC_TAG, formatDiagnosticSummary(diagnostics.snapshot(elapsedMillis), nnapiStatus))
                        lastDiagnosticReportElapsedMillis = elapsedMillis
                    }
                }

                // ── Gating: skip embedding + classifier when confirmed-silent ─────
                if (silenceFrames > silenceHangoverFrames &&
                    chunkCount % maxSilenceSkipFrames.toLong() != 0L) {
                    diagnostics?.recordSilenceGateSkip()
                    if (silenceGateState.enter()) {
                        AcousticJournalBridge.record(
                            type = AcousticEventType.SILENCE_GATE_ENTERED,
                            generationId = generationId,
                        )
                        gateExitDiag?.onGateEntered(chunkCount)?.let { summary ->
                            Log.d(DIAGNOSTIC_TAG, summary)
                        }
                    }
                    continue  // wake word not expected — skip expensive Stage 2/3
                }

                // ── Stage 2: embedding backbone ───────────────────────────────────────
                // Input:  [1, 76, 32, 1] — melRing reshaped; the model expects a channel dim of 1.
                // Output: [1, 1, 1, 96] — one 96-dim embedding vector.
                //
                // melRing is already [76 × 32] row-major.  We reinterpret as [76 × 32 × 1] by
                // passing the same flat array with shape [1, 76, 32, 1] — the channel is implicit
                // (every element maps to exactly one channel-1 position).
                // Capture whether this execution is a gated periodic probe before
                // any state transition may clear the gated state.
                val wasGatedProbe = gateExitDiag != null && silenceGateState.isGated
                if (silenceGateState.onStage2Execution()) {
                    AcousticJournalBridge.record(
                        type = AcousticEventType.STAGE2_RESUMED,
                        generationId = generationId,
                    )
                }
                if (wasGatedProbe) gateExitDiag?.onGatedProbeExecution()
                melState.copyStage2Input(embedInput4D)
                diagnostics?.recordStage2Execution()
                val embedding: FloatArray = computeEmbedding(
                    env, embedSession, embedInputName, embedOutputName, embedInput4D,
                )

                // Accumulate embedding in ring buffer.
                embeddingRing.append(embedding)
                // Classifier-context audio energy: advances only on frames whose
                // Stage-2 embedding was appended, in lockstep with
                // WakeWordEmbeddingRingState membership (debug-gated; no-op when
                // disabled).  Gated frames where Stage 2 was skipped never enter.
                gateExitDiag?.onEmbeddingFrameRms(rms.toFloat())
                if (!embeddingRing.isWindowComplete) continue
                if (!emittedStage3Ready) {
                    emittedStage3Ready = true
                    AcousticJournalBridge.record(
                        type = AcousticEventType.STAGE3_READY,
                        generationId = generationId,
                    )
                }

                // ── Stage 3: classifier over the last 16 embedding frames ─────────────
                // Input:  [1, 16, 96]
                // Output: [1, 1]
                embeddingRing.copyWindow(windowFlat)

                diagnostics?.recordStage3Execution()
                val confidence: Float = computeClassifierConfidence(
                    env, classSession, classInputName, classOutputName, windowFlat,
                )
                // Stage 3 count is retained in low-frequency diagnostics; avoid per-second logs.
                gateExitDiag?.onStage3Evaluation(confidence, chunkCount)


                when {
                    // High-confidence fast path — activate immediately.
                    confidence >= highThreshold -> {
                        Log.i(TAG, "WakeWordDetector: detected (high confidence=$confidence)")
                        AcousticJournalBridge.record(
                            type = AcousticEventType.ACTIVATION_CANDIDATE,
                            generationId = generationId,
                            metadata = {
                                mapOf("confidence" to confidence.toString(), "mode" to "high")
                            },
                        )
                        if (running.compareAndSet(true, false)) {
                            diagnostics?.recordHighConfidenceActivation()
                            AcousticJournalBridge.record(
                                type = AcousticEventType.VERIFIED_ACTIVATION,
                                generationId = generationId,
                                metadata = { mapOf("mode" to "high") },
                            )
                            onDetected()
                        }
                    }

                    // Secondary band — run STT verification if a verifier is wired.
                    verifyWindow != null && confidence >= lowThreshold -> {
                        Log.d(TAG, "WakeWordDetector: low-threshold crossing (confidence=$confidence) — verifying")
                        AcousticJournalBridge.record(
                            type = AcousticEventType.ACTIVATION_CANDIDATE,
                            generationId = generationId,
                            metadata = {
                                mapOf("confidence" to confidence.toString(), "mode" to "low")
                            },
                        )
                        val snapshot = extractPcmSnapshot(pcmRing, pcmRingHead, pcmFilled)
                        val verified = verifyWindow(snapshot)
                        diagnostics?.recordVerifierResult(verified)
                        gateExitDiag?.onLowVerify(verified)
                        if (verified) {
                            Log.i(TAG, "WakeWordDetector: STT verification passed — activating")
                            if (running.compareAndSet(true, false)) {
                                diagnostics?.recordVerifiedActivation()
                                AcousticJournalBridge.record(
                                    type = AcousticEventType.VERIFIED_ACTIVATION,
                                    generationId = generationId,
                                    metadata = { mapOf("mode" to "low") },
                                )
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
            recordDetectorError(generationId, "detector_runtime_failed")
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
                Log.d(DIAGNOSTIC_TAG, formatDiagnosticSummary(it.snapshot(elapsedMillis), nnapiStatus, final = true))
            }
            gateExitDiag?.finish()?.let { summary ->
                Log.d(DIAGNOSTIC_TAG, summary)
            }
        }
    }

    private fun recordDetectorError(generationId: Long, category: String) {
        AcousticJournalBridge.record(
            type = AcousticEventType.DETECTOR_ERROR,
            generationId = generationId,
            metadata = { mapOf("category" to category) },
        )
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

/**
 * Debug-gated per-gate-exit diagnostic lifecycle (#1432 bounded reproduction).
 *
 * Models exactly one real detector lifecycle:
 *
 * ```
 * gate entered
 * → zero or more periodic gated Stage-2 probes
 * → gate exited on voiced audio
 * → zero or more Stage-3 evaluations / verifier attempts
 * → gate entered again or detector generation ended
 * → one summary emitted
 * ```
 *
 * Every emitted summary describes exactly one exit episode: its own Stage-3
 * evaluation count, its own maximum classifier confidence and the offset of
 * that maximum relative to the gate-exit frame (exit frame = offset 0), its
 * own low-verification entry/outcome, the probe executions from the
 * immediately preceding gated interval only, and the captured-audio energy
 * around the maximum-confidence window (the #1432 level discriminator):
 * episode peak RMS over every audio frame from the voiced gate-exit frame
 * through gate re-entry, and peak/mean RMS of the exact 16 embedding-
 * associated frames passed to Stage 3.
 * Initial gate entry emits nothing; gate re-entry without an intervening exit
 * emits nothing; detector shutdown emits only when a real exit episode is
 * open.
 *
 * The holder is only instantiated when the WakeWordDiag DEBUG tag is enabled
 * (same gate as the existing aggregate counters), so it has zero cost in
 * production.  It never touches silence-gate, Stage-2/3, threshold, verifier
 * or activation behaviour.  No PCM, transcripts or per-frame output.
 */
internal class WakeWordGateExitDiagnostics(
    private val generationId: Long,
) {
    /** True while a gate-exit episode is open (exit occurred, gate not re-entered). */
    var episodeOpen: Boolean = false
        private set

    /** Periodic Stage-2 probe executions since the last gate entry. */
    var gatedProbeExecutions: Long = 0
        private set

    private var stage3Evaluations = 0
    private var maxConfidence = -1f
    private var maxConfidenceOffsetFrames = -1
    private var lowVerifyEntered = false
    private var lowVerifyAccepted = false
    private var exitChunk = 0

    /** Peak RMS of the frames captured while the episode was open (-1 = none). */
    private var episodePeakRms = -1f

    /** Peak / mean RMS of the 16 embedding-associated frames of the max-confidence window (-1 = none). */
    private var maxWindowPeakRms = -1f
    private var maxWindowMeanRms = -1f

    // Rolling RMS of the last EMBEDDING_FRAMES frames whose Stage-2 embedding
    // was appended to the production embedding ring — the audio that produced
    // the classifier window.  Membership mirrors WakeWordEmbeddingRingState:
    // same capacity, head-overwrite and completeness semantics; the ring
    // advances only on embedding appends (gated frames where Stage 2 is
    // skipped never enter).  Peak/mean are order-independent, so the ring is
    // not kept chronologically ordered.
    private val embeddingRmsRing = FloatArray(EMBEDDING_FRAMES)
    private var embeddingRmsHead = 0
    private var embeddingRmsFilled = 0

    /**
     * Records the RMS of one processed audio frame against the open episode.
     * Called after the silence-gate transition for that frame, so the voiced
     * frame that triggers [onGateExited] is included in [episodePeakRms] and
     * gated pre-exit frames are excluded.
     */
    fun onEpisodeFrameRms(rms: Float) {
        if (episodeOpen && rms > episodePeakRms) episodePeakRms = rms
    }

    /**
     * Records the RMS of one frame whose Stage-2 embedding was appended to
     * the production embedding ring.  Called immediately after the append so
     * the diagnostic ring advances in lockstep with the classifier window.
     */
    fun onEmbeddingFrameRms(rms: Float) {
        embeddingRmsRing[embeddingRmsHead] = rms
        embeddingRmsHead = (embeddingRmsHead + 1) % EMBEDDING_FRAMES
        if (embeddingRmsFilled < EMBEDDING_FRAMES) embeddingRmsFilled++
    }

    /**
     * Records a gate entry.  Returns the summary of the just-closed exit
     * episode, or `null` when no exit episode was open (initial gate entry or
     * a re-entry without an intervening exit).  Always starts a fresh gated
     * interval, so probe counts never bleed between gated intervals.
     */
    fun onGateEntered(chunk: Int): String? {
        val summary = if (episodeOpen) currentSummary() else null
        episodeOpen = false
        resetEpisode()
        gatedProbeExecutions = 0
        return summary
    }

    /** Records the gate exit on voiced audio that starts an exit episode. */
    fun onGateExited(chunk: Int) {
        resetEpisode()
        exitChunk = chunk
        episodeOpen = true
    }

    /** Records one Stage-3 evaluation at [chunk], tracked only for the open episode. */
    fun onStage3Evaluation(confidence: Float, chunk: Int) {
        if (!episodeOpen) return
        stage3Evaluations++
        if (confidence > maxConfidence) {
            maxConfidence = confidence
            maxConfidenceOffsetFrames = chunk - exitChunk
            // Snapshot the audio energy of the classifier window only when the
            // embedding-context ring is complete.  Stage 3 only runs on a
            // complete 16-embedding window, so the RMS context must be complete
            // too; fail closed (report none) rather than averaging a partial
            // ring that would misrepresent the window.
            if (embeddingRmsFilled >= EMBEDDING_FRAMES) {
                var peak = 0f
                var sum = 0f
                for (i in 0 until EMBEDDING_FRAMES) {
                    val v = embeddingRmsRing[i]
                    sum += v
                    if (v > peak) peak = v
                }
                maxWindowPeakRms = peak
                maxWindowMeanRms = sum / EMBEDDING_FRAMES
            } else {
                maxWindowPeakRms = -1f
                maxWindowMeanRms = -1f
            }
        }
    }

    /** Records the outcome of a low-band verifier attempt within the open episode. */
    fun onLowVerify(accepted: Boolean) {
        if (!episodeOpen) return
        lowVerifyEntered = true
        lowVerifyAccepted = accepted
    }

    /** Records one gated periodic Stage-2 probe execution (pre-episode interval). */
    fun onGatedProbeExecution() {
        gatedProbeExecutions++
    }

    /**
     * Detector shutdown.  Returns the final summary when a real exit episode
     * is still open, otherwise `null`.
     */
    fun finish(): String? = if (episodeOpen) currentSummary() else null

    private fun currentSummary(): String = buildGateExitSummary(
        generationId = generationId,
        stage3Evaluations = stage3Evaluations,
        maxConfidence = maxConfidence,
        maxConfidenceOffsetFrames = maxConfidenceOffsetFrames,
        lowVerifyEntered = lowVerifyEntered,
        lowVerifyAccepted = lowVerifyAccepted,
        gatedProbeExecutions = gatedProbeExecutions,
        episodePeakRms = episodePeakRms,
        maxWindowPeakRms = maxWindowPeakRms,
        maxWindowMeanRms = maxWindowMeanRms,
    )

    private fun resetEpisode() {
        stage3Evaluations = 0
        maxConfidence = -1f
        maxConfidenceOffsetFrames = -1
        lowVerifyEntered = false
        lowVerifyAccepted = false
        exitChunk = 0
        episodePeakRms = -1f
        maxWindowPeakRms = -1f
        maxWindowMeanRms = -1f
    }
}

/**
 * One bounded aggregate summary per silence-gate exit episode (#1432
 * reproduction): how many Stage-3 evaluations the classifier performed during
 * that exit episode, the maximum confidence observed, its offset in detector
 * frames from the gate-exit frame (exit frame = offset 0), whether a low-band
 * STT verification was entered and its boolean outcome, how many gated
 * periodic probe executions preceded the episode from the immediately
 * preceding gated interval only (ring composition context), and the captured
 * audio energy: peak RMS over every audio frame of the episode (voiced
 * gate-exit frame through gate re-entry) and peak/mean RMS of the exact 16
 * embedding-associated frames passed to Stage 3 for the max-confidence
 * window (reported `none` when the classifier context was incomplete).  No
 * PCM, no transcripts and no per-frame output; emitted only when WakeWordDiag
 * DEBUG is enabled.
 */
internal fun buildGateExitSummary(
    generationId: Long,
    stage3Evaluations: Int,
    maxConfidence: Float,
    maxConfidenceOffsetFrames: Int,
    lowVerifyEntered: Boolean,
    lowVerifyAccepted: Boolean,
    gatedProbeExecutions: Long,
    episodePeakRms: Float = -1f,
    maxWindowPeakRms: Float = -1f,
    maxWindowMeanRms: Float = -1f,
): String = buildString {
    append("WakeWordDetector: gateExitSummary")
    append(" gen=").append(generationId)
    append(" stage3Evals=").append(stage3Evaluations)
    append(" maxConfidence=")
    if (maxConfidence >= 0f) append(maxConfidence.toString()) else append("none")
    append(" maxConfidenceOffsetFrames=").append(maxConfidenceOffsetFrames)
    append(" lowVerifyEntered=").append(lowVerifyEntered)
    append(" lowVerifyAccepted=").append(lowVerifyAccepted)
    append(" gatedProbeExecutions=").append(gatedProbeExecutions)
    append(" episodePeakRms=")
    if (episodePeakRms >= 0f) append(episodePeakRms.toString()) else append("none")
    append(" maxWindowPeakRms=")
    if (maxWindowPeakRms >= 0f) append(maxWindowPeakRms.toString()) else append("none")
    append(" maxWindowMeanRms=")
    if (maxWindowMeanRms >= 0f) append(maxWindowMeanRms.toString()) else append("none")
}
