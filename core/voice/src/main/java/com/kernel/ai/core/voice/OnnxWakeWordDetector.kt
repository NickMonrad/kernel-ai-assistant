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
private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

// ── Frame / chunk sizes ───────────────────────────────────────────────────────
/**
 * openWakeWord processes audio in 80ms frames = 1280 samples at 16kHz.
 * This is the fundamental unit of the melspectrogram frontend.
 */
private const val FRAME_SAMPLES = 1_280 // 80ms × 16 000 Hz

// ── Embedding ring buffer ─────────────────────────────────────────────────────
/**
 * Number of backbone embedding frames fed to the classifier at each step.
 *
 * openWakeWord's default model uses a sliding window of 28 embedding frames
 * (~2.24 s of context) as the classifier input.  Verified from the training
 * notebook: `window = features[i : i+28][None,]`.
 *
 * Each frame is [EMBEDDING_DIM]-dimensional (Google Speech Embedding backbone
 * produces 96-dim vectors).  The classifier receives shape [1, 28, 96] → flattened
 * to [1, 2688] by the first `nn.Flatten()` layer.
 *
 * NOTE: If you swap the backbone for a different model, update these two constants
 * and re-export the classifier.  Run
 * `python -c "import openwakeword; print(openwakeword.utils.get_embedding_shape())"` to verify.
 */
private const val EMBEDDING_FRAMES = 28
private const val EMBEDDING_DIM = 96

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
 * Always-on wake word detector implementing the openWakeWord 3-stage ONNX pipeline.
 *
 * ## Pipeline
 * ```
 * AudioRecord (16kHz mono)
 *   → 80ms chunks (1280 samples)
 *   → [Stage 1] melspectrogram.onnx    — raw PCM → mel-spectrogram features
 *   → [Stage 2] embedding_model.onnx   — mel features → 96-dim embedding vector
 *   → ring buffer (last 28 embeddings) — ~2.24s of acoustic context
 *   → [Stage 3] hey_jandal.onnx        — embedding window → confidence [0,1]
 *   → threshold check → onDetected()
 * ```
 *
 * ## Model contracts
 *
 * ### Stage 1 — melspectrogram.onnx
 * - Input:  `float32[1, 1280]` — one 80ms frame of normalised PCM in [-1, 1]
 * - Output: `float32[1, 32, 32]` — mel spectrogram patch (verify with Netron)
 *
 * ### Stage 2 — embedding_model.onnx (Google Speech Embedding)
 * - Input:  mel spectrogram patch from Stage 1 (shape as above)
 * - Output: `float32[1, 96]` — one embedding vector per 80ms frame
 *
 * ### Stage 3 — hey_jandal.onnx (trained by #984)
 * - Input:  `float32[1, 28, 96]` — sliding window of 28 embedding frames
 * - Output: `float32[1, 1]` — wake word confidence in [0, 1]
 * - File:   assets/models/wakeword/hey_jandal.onnx
 *
 * ## Availability
 * [isAvailable] returns false and [start] is a no-op when any model file is absent,
 * so the app ships fine before the models are ready.  Placing all three files in
 * assets/models/wakeword/ activates the feature automatically.
 *
 * ## Shape verification
 * If you change backbone or retrain with a different context window, verify the
 * actual ONNX input/output names and shapes with Netron (https://netron.app) and
 * update [EMBEDDING_FRAMES], [EMBEDDING_DIM], and the input tensor names below.
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

    override fun start(onDetected: () -> Unit) {
        val bytes = modelBytes
        if (bytes == null) {
            Log.d(TAG, "WakeWordDetector: start() called but model(s) absent — no-op")
            return
        }
        if (!running.compareAndSet(false, true)) return

        // Read the threshold on the caller's thread before entering the detection
        // thread.  This avoids runBlocking inside a thread whose dispatcher is unknown
        // and eliminates any risk of deadlock if the DataStore flow ever emits on Main.
        val threshold = runBlocking { wakeWordPreferences.confidenceThreshold.first() }
        Log.i(TAG, "WakeWordDetector: starting 3-stage ONNX pipeline — threshold=$threshold")

        detectionThread = Thread(
            { runDetectionLoop(bytes, threshold, onDetected) },
            "wake-word-detector",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running.set(false)
        detectionThread?.interrupt()
        detectionThread = null
        Log.d(TAG, "WakeWordDetector: stopped")
    }

    private fun runDetectionLoop(bytes: ModelBytes, threshold: Float, onDetected: () -> Unit) {
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
            // sessionOptions must be closed here too — it holds a native handle.
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
        val embeddingRing = Array(EMBEDDING_FRAMES) { FloatArray(EMBEDDING_DIM) }
        var ringHead = 0
        var framesAccumulated = 0
        val chunk     = ShortArray(FRAME_SAMPLES)
        val framePcm  = FloatArray(FRAME_SAMPLES)
        // windowFlat is re-filled in place each iteration; allocated once here.
        val windowFlat = FloatArray(EMBEDDING_FRAMES * EMBEDDING_DIM)

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

                // Normalise 16-bit PCM to [-1, 1].
                for (i in 0 until FRAME_SAMPLES) {
                    framePcm[i] = chunk[i] / 32768f
                }

                // --- Stage 1: melspectrogram ---
                // OrtSession.Result implements AutoCloseable; use{} guarantees the native
                // output tensor is released even if the downstream session call throws.
                val embedding: FloatArray = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(framePcm),
                    longArrayOf(1L, FRAME_SAMPLES.toLong()),
                ).use { melInput ->
                    // --- Stage 2: embedding backbone ---
                    melsSession.run(mapOf(melsInputName to melInput)).use { melOutput ->
                        val melTensor = melOutput[melsOutputName].get() as OnnxTensor
                        embedSession.run(mapOf(embedInputName to melTensor)).use { embedOutput ->
                            val embedTensor = embedOutput[embedOutputName].get() as OnnxTensor
                            // Copy out before the use{} block closes the result.
                            ((embedTensor.value as Array<*>)[0] as FloatArray).copyOf()
                        }
                    }
                }

                embedding.copyInto(embeddingRing[ringHead])
                ringHead = (ringHead + 1) % EMBEDDING_FRAMES
                if (framesAccumulated < EMBEDDING_FRAMES) framesAccumulated++
                if (framesAccumulated < EMBEDDING_FRAMES) continue

                // --- Stage 3: classifier over the last 28 frames ---
                // Flatten ring buffer into chronological order in the pre-allocated array.
                for (f in 0 until EMBEDDING_FRAMES) {
                    val frameIdx = (ringHead + f) % EMBEDDING_FRAMES
                    embeddingRing[frameIdx].copyInto(windowFlat, f * EMBEDDING_DIM)
                }

                val confidence: Float = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(windowFlat),
                    longArrayOf(1L, EMBEDDING_FRAMES.toLong(), EMBEDDING_DIM.toLong()),
                ).use { classInput ->
                    classSession.run(mapOf(classInputName to classInput)).use { classOutput ->
                        val classTensor = classOutput[classOutputName].get() as OnnxTensor
                        ((classTensor.value as Array<*>)[0] as FloatArray)[0]
                    }
                }

                if (confidence >= threshold) {
                    Log.i(TAG, "WakeWordDetector: detected — confidence=$confidence")
                    // compareAndSet prevents re-triggering if stop() races with us here.
                    if (running.compareAndSet(true, false)) {
                        onDetected()
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordDetector: error in detection loop", e)
        } finally {
            audioRecord.stop()
            audioRecord.release()
            melsSession.close()
            embedSession.close()
            classSession.close()
            sessionOptions.close()
            running.set(false)
            Log.d(TAG, "WakeWordDetector: detection loop exited")
        }
    }
}
