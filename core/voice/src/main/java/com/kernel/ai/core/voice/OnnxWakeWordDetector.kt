package com.kernel.ai.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

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

    override fun start(onDetected: () -> Unit, verifyWindow: ((ShortArray) -> Boolean)?) {
        val bytes = modelBytes
        if (bytes == null) {
            Log.d(TAG, "WakeWordDetector: start() called but model(s) absent — no-op")
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
        activeAudioRecord?.stop()   // unblocks AudioRecord.read() immediately
        detectionThread?.interrupt()
        detectionThread = null
        Log.d(TAG, "WakeWordDetector: stopped")
    }

    @Suppress("LongMethod") // hot path — intentional single function to minimise call overhead
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

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "WakeWordDetector: AudioRecord failed to initialise")
            running.set(false)
            return
        }
        activeAudioRecord = audioRecord


        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
        }

        val melsSession  = runCatching { env.createSession(bytes.melspectrogram, sessionOptions) }
            .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load melspectrogram.onnx", e); null }
        val embedSession = runCatching { env.createSession(bytes.embedding, sessionOptions) }
            .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load embedding_model.onnx", e); null }
        val classSession = runCatching { env.createSession(bytes.classifier, sessionOptions) }
            .getOrElse { e -> Log.e(TAG, "WakeWordDetector: failed to load hey_jandal.onnx", e); null }

        if (melsSession == null || embedSession == null || classSession == null) {
            sessionOptions.close()
            melsSession?.close()
            embedSession?.close()
            classSession?.close()
            audioRecord.release()
            running.set(false)
            return
        }

        // Resolve ONNX node names once at startup (avoids hard-coding strings).
        val melsInputName   = melsSession.inputNames.first()
        val melsOutputName  = melsSession.outputNames.first()
        val embedInputName  = embedSession.inputNames.first()
        val embedOutputName = embedSession.outputNames.first()
        val classInputName  = classSession.inputNames.first()
        val classOutputName = classSession.outputNames.first()

        // Pre-allocate all hot-loop buffers — zero heap churn during detection.
        //
        // melRing: sliding window of MEL_RING_SIZE rows × MEL_BINS columns, stored flat row-major.
        // Each 80ms chunk appends MEL_ROWS_PER_CHUNK new rows; the ring slides by that many rows
        // each step.  Once full, we build the [1, 76, 32, 1] embedding model input from it.
        val melRing = FloatArray(MEL_RING_SIZE * MEL_BINS)   // [76 × 32]
        var melRowsFilled = 0
        var chunkCount = 0

        val embeddingRing = Array(EMBEDDING_FRAMES) { FloatArray(EMBEDDING_DIM) }
        var embRingHead = 0
        var embFramesAccumulated = 0
        var inferenceCount = 0L
        val chunk    = ShortArray(FRAME_SAMPLES)
        val framePcm = FloatArray(FRAME_SAMPLES)
        // windowFlat: [16 × 96] flattened, re-filled in place each classifier call.
        val windowFlat = FloatArray(EMBEDDING_FRAMES * EMBEDDING_DIM)
        // embedInput4D: [1, 76, 32, 1] — reshaped mel ring for the embedding model.
        val embedInput4D = FloatArray(MEL_RING_SIZE * MEL_BINS)

        // PCM ring buffer for STT verification (pre-allocated; only used when verifyWindow != null).
        val pcmRing     = if (verifyWindow != null) ShortArray(VERIFY_RING_SAMPLES) else ShortArray(0)
        var pcmRingHead = 0
        var pcmFilled   = 0
        val replayChunkRing = Array(replayFrames) { ShortArray(FRAME_SAMPLES) }
        var replayHead = 0
        var replayFilled = 0
        var silenceFrames = 0
        var voicedFrameStreak = 0
        var adaptiveRmsBaseline = 0.0
        var gatedFramesSkipped = 0L
        var fullInferenceDebt = replayFrames
        var maxObservedReplay = 0

        try {
            audioRecord.startRecording()
            Log.d(TAG, "WakeWordDetector: recording started")

            while (running.get() && !Thread.currentThread().isInterrupted) {
                // Read exactly one 80ms frame; abort if AudioRecord signals an error.
                var totalRead = 0
                while (totalRead < FRAME_SAMPLES && running.get()) {
                    val read = audioRecord.read(chunk, totalRead, FRAME_SAMPLES - totalRead)
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

                // Keep a rolling PCM history so speech onset can replay enough context to rebuild
                // the mel/embedding/classifier state before live inference resumes.
                chunk.copyInto(replayChunkRing[replayHead], 0, 0, FRAME_SAMPLES)
                replayHead = (replayHead + 1) % replayFrames
                if (replayFilled < replayFrames) replayFilled++

                // ── Adaptive noise gating ──────────────────────────────────────────
                // Track the minimum observed RMS as an adaptive silence baseline.
                // This allows the threshold to work correctly across different
                // environments (quiet bedroom vs office background hum).
                if (adaptiveRmsBaseline <= 0.0 || rms < adaptiveRmsBaseline * 1.05) {
                    adaptiveRmsBaseline = rms
                }
                // Every ~80 seconds, allow a tiny upward drift so the baseline can
                // adjust to gradually louder environments over time.
                if (chunkCount % 1000 == 0 && rms >= adaptiveRmsBaseline) {
                    adaptiveRmsBaseline *= 1.001
                }

                // ── Debounced voice detection ──────────────────────────────────────
                // Require 3+ consecutive frames with RMS ≥ threshold (240ms) before
                // treating audio as speech.  A single 80ms transient (door, tap, car
                // passing) no longer resets the silence timer.
                val isFrameVoiced = rms >= silenceRmsThreshold
                voicedFrameStreak = if (isFrameVoiced) voicedFrameStreak + 1 else 0
                val voiced = voicedFrameStreak >= 3

                if (voiced) {
                    // When voice resumes after deep silence (gating was active), the
                    // replay ring is full of stale silence frames.  Replaying them just
                    // delays live audio processing — skip the replay and let the mel/
                    // embedding rings fill naturally with fresh audio (~1.3s at 12.5/s).
                    // Brief voice transients (RMS briefly dips then returns quickly) still
                    // use replay to preserve ring buffer continuity.
                    val wasGated = silenceFrames > silenceHangoverFrames
                    silenceFrames = 0
                    if (fullInferenceDebt == 0 && !wasGated) {
                        fullInferenceDebt = replayFilled.coerceAtLeast(1)
                        if (fullInferenceDebt > maxObservedReplay) maxObservedReplay = fullInferenceDebt
                    }
                } else {
                    silenceFrames++
                }

                if (!voiced && silenceFrames > silenceHangoverFrames && fullInferenceDebt <= 0) {
                    fullInferenceDebt = if (chunkCount % maxSilenceSkipFrames.toLong() == 0L) 1 else 0
                    if (fullInferenceDebt == 0) {
                        gatedFramesSkipped++
                        continue
                    }
                }
                // Log mic activity every ~8s to confirm AudioRecord is delivering data.
                // Gated frames are skipped at the `continue` above and never reach here.
                if (chunkCount % 100 == 0) {
                    Log.d(
                        TAG,
                        "WakeWordDetector: alive chunk=$chunkCount rms=${"%.1f".format(rms)} " +
                            "base=${"%.1f".format(adaptiveRmsBaseline)} " +
                            "thresh=$silenceRmsThreshold voiced=$voiced " +
                            "debt=$fullInferenceDebt melFilled=$melRowsFilled " +
                            "embAcc=$embFramesAccumulated ongoingSkips=$gatedFramesSkipped",
                    )
                }

                val frameForInference = if (fullInferenceDebt > 0) {
                    val replayIndex = ((replayHead - fullInferenceDebt) + replayFrames) % replayFrames
                    fullInferenceDebt--
                    replayChunkRing[replayIndex]
                } else {
                    chunk
                }

                // openWakeWord's mel model expects raw 16-bit PCM values cast to float32
                // (range ±32768), NOT normalised to [-1, 1]. Using the wrong scale shifts
                // the mel output by ~88 units, putting embeddings completely out of the
                // distribution the classifier was trained on (verified empirically).
                for (i in 0 until FRAME_SAMPLES) {
                    framePcm[i] = frameForInference[i].toFloat()
                }

                // ── Stage 1: melspectrogram ───────────────────────────────────────────
                // Input:  [1, 1280] float32 PCM
                // Output: [1, 1, 5, 32] mel spectrogram patch (5 rows × 32 bins per chunk)
                val melRows: FloatArray = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(framePcm),
                    longArrayOf(1L, FRAME_SAMPLES.toLong()),
                ).use { melIn ->
                    melsSession.run(mapOf(melsInputName to melIn)).use { melOut ->
                        val t = melOut[melsOutputName].get() as OnnxTensor
                        // t.value is Array<Array<Array<FloatArray>>> with shape [1,1,5,32].
                        // Flatten the [5,32] patch and apply the openWakeWord mel transform:
                        //   value / 10.0 + 2.0
                        // This normalisation is applied by the reference Android implementation
                        // (Re-MENTIA/openwakeword-android-kt MelSpectrogram.applyMelSpecTransform)
                        // and matches the preprocessing used during training. Without it the
                        // classifier sees out-of-distribution inputs and outputs ~0.001 for all audio.
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
                    // Ring full — shift left by MEL_ROWS_PER_CHUNK, append at the tail.
                    melRing.copyInto(melRing, 0, MEL_ROWS_PER_CHUNK * MEL_BINS, MEL_RING_SIZE * MEL_BINS)
                    melRows.copyInto(melRing, (MEL_RING_SIZE - MEL_ROWS_PER_CHUNK) * MEL_BINS)
                } else {
                    // Ring not yet full — append as many rows as fit (MEL_RING_SIZE may not be
                    // a multiple of MEL_ROWS_PER_CHUNK, so clamp to avoid OOB on the last chunk).
                    val rowsToInsert = minOf(MEL_ROWS_PER_CHUNK, MEL_RING_SIZE - melRowsFilled)
                    melRows.copyInto(melRing, melRowsFilled * MEL_BINS, 0, rowsToInsert * MEL_BINS)
                    melRowsFilled += rowsToInsert
                }
                if (melRowsFilled < MEL_RING_SIZE) continue // need more audio before first embedding

                // ── Stage 2: embedding backbone ───────────────────────────────────────
                // Input:  [1, 76, 32, 1] — melRing reshaped; the model expects a channel dim of 1.
                // Output: [1, 1, 1, 96] — one 96-dim embedding vector.
                //
                // melRing is already [76 × 32] row-major.  We reinterpret as [76 × 32 × 1] by
                // passing the same flat array with shape [1, 76, 32, 1] — the channel is implicit
                // (every element maps to exactly one channel-1 position).
                melRing.copyInto(embedInput4D)
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
                inferenceCount++
                // Log every inference during first 5s, then every ~1s (every 12 inferences).
                if (inferenceCount <= 20 || inferenceCount % 12 == 0L) {
                    Log.d(TAG, "WakeWordDetector: inference #$inferenceCount confidence=${"%.4f".format(confidence)}")
                }


                when {
                    // High-confidence fast path — activate immediately.
                    confidence >= highThreshold -> {
                        Log.i(TAG, "WakeWordDetector: detected (high confidence=$confidence)")
                        if (running.compareAndSet(true, false)) {
                            onDetected()
                        }
                    }

                    // Secondary band — run STT verification if a verifier is wired.
                    verifyWindow != null && confidence >= lowThreshold -> {
                        Log.d(TAG, "WakeWordDetector: low-threshold crossing (confidence=$confidence) — verifying")
                        val snapshot = extractPcmSnapshot(pcmRing, pcmRingHead, pcmFilled)
                        if (verifyWindow(snapshot)) {
                            Log.i(TAG, "WakeWordDetector: STT verification passed — activating")
                            if (running.compareAndSet(true, false)) {
                                onDetected()
                            }
                        } else {
                            Log.d(TAG, "WakeWordDetector: STT verification rejected — continuing")
                        }
                    }

                    // Below all thresholds — ignore.
                    else -> Unit
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordDetector: error in detection loop", e)
        } finally {
            activeAudioRecord = null
            runCatching { audioRecord.stop() }
            audioRecord.release()
            melsSession.close()
            embedSession.close()
            classSession.close()
            sessionOptions.close()
            running.set(false)
            Log.d(TAG, "WakeWordDetector: detection loop exited — " +
                "inferences=$inferenceCount gatedSkips=$gatedFramesSkipped " +
                "maxReplayFrames=$maxObservedReplay finalBaseline=${"%.1f".format(adaptiveRmsBaseline)}")
        }
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
