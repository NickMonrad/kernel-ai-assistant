package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
 * VoiceInputController backed by Parakeet CTC via TFLite Interpreter.
 *
 * Uses the LiteRT-community TFLite conversion of NVIDIA's Parakeet-CTC FastConformer model.
 * Input: raw 16 kHz mono PCM16 audio → MFCC features (80-dim mel filters) → TFLite inference →
 * CTC greedy decoding → text.
 *
 * **Design: push-to-talk / batch-only.** Parakeet CTC is bounded to ~5 seconds of audio
 * (the "5s" variant in the model files). No streaming partials.
 *
 * Threading model:
 * - `startListening`/`stopListening` are serialised by [sessionMutex].
 * - Model + tokenizer are cached after first use.
 * - Audio capture runs on [Dispatchers.IO]; [_events] is a [MutableSharedFlow].
 */
@Singleton
class ParakeetVoiceInputController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInputController {

    companion object {
        private const val TAG = "ParakeetSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = (0.1 * SAMPLE_RATE).toInt() // 100 ms
        private const val LISTEN_TIMEOUT_MS = 15_000L

        /** Default model file — generic INT8 variant (~596 MB). */
        const val MODEL_FILE = "parakeet-ctc-0.6b_i8.tflite"

        /** SM8550-specific model file — FP32 variant (~1.2 GB, Qualcomm-optimised). */
        const val MODEL_FILE_SM8550 = "parakeet-ctc-0.6b_SM8550_i8.tflite"

        /** SentencePiece tokeniser model file. */
        const val TOKENIZER_FILE = "parakeet-ctc-tokenizer.model"

        /**
         * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
         */
        fun String.containsWakePhrase(): Boolean {
            val lower = lowercase()
            val namePattern = Regex("""\b(?:hey|a)\s*(?:jandal|jandel|handel|handal|hando)\b""")
            return namePattern.containsMatchIn(lower)
        }
    }

    // ── Model state ────────────────────────────────────────────────────────────

    private var interpreter: Interpreter? = null
    private val modelMutex = Mutex()

    private var tokenizer: ParakeetTokenizer? = null

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

            if (!ensureState()) {
                return@withLock VoiceInputStartResult.Unavailable(
                    "Parakeet STT model not available — download it from Settings → Voice."
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
     * Records until [stopListening] is called or timeout, then transcribes via Parakeet CTC.
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

        // Extract MFCC features and run inference
        val mfcc = ParakeetMfccExtractor().extract(pcmBuffer)
        val decoded = runInference(mfcc)

        if (decoded.isNotBlank()) {
            _events.tryEmit(VoiceInputEvent.Transcript(mode, decoded))
        } else {
            _events.tryEmit(
                VoiceInputEvent.Error(mode, "I didn't catch anything — please try again.")
            )
        }
        _events.tryEmit(VoiceInputEvent.ListeningStopped(mode))
    }

    // ── TFLite inference ───────────────────────────────────────────────────────

    /**
     * Runs Parakeet CTC inference on MFCC features, returns decoded text.
     *
     * Parakeet CTC takes [1, numFrames, 80] MFCC input and produces
     * [1, numFrames, vocabSize] token probabilities. We do greedy argmax + blank removal.
     */
    private fun runInference(mfcc: FloatArray): String {
        val interp = interpreter ?: return ""
        val tok = tokenizer ?: return ""

        val numFrames = mfcc.size / 80
        if (numFrames == 0) return ""

        // TFLite CTC model expects input shape [1, numFrames, 80]
        val input = FloatArray(1 * numFrames * 80)
        System.arraycopy(mfcc, 0, input, 0, mfcc.size)

        // Output shape: [1, numFrames, vocabSize]
        // Parakeet CTC vocab size is 128 (including blank)
        val vocabSize = 128
        val output = FloatArray(numFrames * vocabSize)

        // FIX: Use interp.run(tensor, output) — not runForMultipleInputsOutputs
        interp.run(input, output)

        // Greedy CTC decoding: argmax over vocab dimension, then blank removal
        val tokenIds = IntArray(numFrames)
        for (t in 0 until numFrames) {
            val base = t * vocabSize
            var bestId = 0
            var bestScore = -Float.MAX_VALUE
            for (v in 0 until vocabSize) {
                val score = output[base + v]
                if (score > bestScore) {
                    bestScore = score
                    bestId = v
                }
            }
            tokenIds[t] = bestId
        }

        return tok.decodeCtc(tokenIds)
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

    /**
     * Ensures the Parakeet TFLite model and tokenizer are loaded.
     * Returns false if model files are missing or loading fails.
     */
    private suspend fun ensureState(): Boolean {
        if (interpreter != null && tokenizer != null) return true

        return modelMutex.withLock {
            if (interpreter != null && tokenizer != null) return@withLock true

            val modelFile = withContext(Dispatchers.IO) { findModelFile() }
            val tokenizerFile = withContext(Dispatchers.IO) { tokenizerFile() }

            if (modelFile == null) {
                Log.w(TAG, "Parakeet model file not found")
                return@withLock false
            }
            if (!tokenizerFile.exists()) {
                Log.w(TAG, "Tokenizer file missing: ${tokenizerFile.absolutePath}")
                return@withLock false
            }

            try {
                val mapped = mapModelFile(modelFile)
                val options = Interpreter.Options().apply { numThreads = 4 }
                interpreter = Interpreter(mapped, options)

                tokenizer = ParakeetTokenizer(tokenizerFile)
                Log.i(TAG, "Parakeet CTC ready: model=${modelFile.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Parakeet model", e)
                interpreter?.close()
                interpreter = null
                false
            }
        }
    }

    private fun findModelFile(): File? {
        val modelsDir = modelsDir()
        // Try SM8550 variant first (device-specific, Qualcomm-optimised)
        val sm8550 = File(modelsDir, MODEL_FILE_SM8550)
        if (sm8550.exists() && sm8550.length() > 0) return sm8550
        // Fall back to generic INT8
        val generic = File(modelsDir, MODEL_FILE)
        if (generic.exists() && generic.length() > 0) return generic
        return null
    }

    private fun tokenizerFile(): File = File(modelsDir(), TOKENIZER_FILE)

    private fun mapModelFile(file: File): MappedByteBuffer =
        FileInputStream(file).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }

    /** Releases the TFLite interpreter. */
    fun cleanup() {
        interpreter?.close()
        interpreter = null
        tokenizer = null
    }

    // ── Availability ───────────────────────────────────────────────────────────

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val model = findModelFile() != null
        val tok = tokenizerFile().exists() && tokenizerFile().length() > 0
        model && tok
    }
}

// ── MFCC Feature Extraction ──────────────────────────────────────────────────

/**
 * Pure-Kotlin MFCC feature extractor for Parakeet CTC.
 *
 * Converts raw 16 kHz mono PCM16 audio to a sequence of 80-dim MFCC frames.
 * Parameters match the Parakeet CTC model's expected input:
 * - 25 ms window, 10 ms hop
 * - 80 mel filters
 * - 8000 Hz bandwidth (Nyquist for 16 kHz)
 *
 * This is a simplified implementation suitable for batch-only push-to-talk.
 */
class ParakeetMfccExtractor {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE_MS = 25L
        private const val HOP_SIZE_MS = 10L
        private const val NUM_MEL_FILTERS = 80
        private const val FFT_SIZE = 512
        private const val NUM_CEPSTRUM = 80
        private const val MIN_LOG_DELTA = 1e-10f

        // Precomputed triangular window
        private val WINDOW = FloatArray(FFT_SIZE) { i ->
            (0.5f - 0.5f * kotlin.math.cos(2.0 * kotlin.math.PI * i / (FFT_SIZE - 1))).toFloat()
        }

        // Precomputed mel filterbank
        private val MEL_FILTERS: Array<FloatArray> = computeMelFiltersStatic(NUM_MEL_FILTERS, FFT_SIZE, SAMPLE_RATE)

        private fun computeMelFiltersStatic(numFilters: Int, fftSize: Int, sampleRate: Int): Array<FloatArray> {
            val halfSize = fftSize / 2 + 1
            val melLow = 2595f * kotlin.math.log10(1f / 700f)
            val melHigh = 2595f * kotlin.math.log10(1f + (sampleRate / 2f) / 700f)

            val melPoints = FloatArray(numFilters + 2)
            for (i in melPoints.indices) {
                melPoints[i] = melLow + i * (melHigh - melLow) / (numFilters + 1)
            }

            val hzPoints = FloatArray(numFilters + 2)
            for (i in hzPoints.indices) {
                hzPoints[i] = 700f * (Math.pow(10.0, melPoints[i] / 2595.0) - 1.0).toFloat()
            }

            val binPoints = hzPoints.map { 1 + (it * (fftSize + 1) / sampleRate).toInt() }.toIntArray()

            val filters = Array(numFilters) { FloatArray(halfSize) }
            for (i in 0 until numFilters) {
                for (f in binPoints[i] until binPoints[i + 1]) {
                    val denom = binPoints[i + 1] - binPoints[i]
                    filters[i][f] = if (denom == 0) 0f else (f - binPoints[i]).toFloat() / denom
                }
                for (f in binPoints[i + 1] until binPoints[i + 2]) {
                    val denom = binPoints[i + 2] - binPoints[i + 1]
                    filters[i][f] = if (denom == 0) 0f else (binPoints[i + 2] - f).toFloat() / denom
                }
            }

            return filters
        }
    }

    /**
     * Extract MFCC features from raw PCM16 audio.
     *
     * @param pcm 16 kHz mono PCM16 samples
     * @return 1D FloatArray of size [numFrames * NUM_CEPSTRUM]
     */
    fun extract(pcm: List<Short>): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)

        val windowSamples = (WINDOW_SIZE_MS * SAMPLE_RATE / 1000).toInt()
        val hopSamples = (HOP_SIZE_MS * SAMPLE_RATE / 1000).toInt()

        val numFrames = ((pcm.size - windowSamples) / hopSamples) + 1
        if (numFrames <= 0) return FloatArray(0)

        val features = FloatArray(numFrames * NUM_CEPSTRUM)

        for (frame in 0 until numFrames) {
            val offset = frame * hopSamples
            // Apply window and convert to float
            val windowed = FloatArray(FFT_SIZE)
            for (i in 0 until windowSamples) {
                windowed[i] = pcm[offset + i] / 32768f * WINDOW[i]
            }

            // FFT magnitude spectrum (DFT)
            val magnitudes = computeMagnitudeSpectrum(windowed)

            // Apply mel filterbank
            val melSpectrum = computeMelSpectrum(magnitudes)

            // Log energy
            for (i in 0 until NUM_CEPSTRUM) {
                features[frame * NUM_CEPSTRUM + i] =
                    Math.log((melSpectrum[i] + MIN_LOG_DELTA).toDouble()).toFloat()
            }
        }

        return features
    }

    /** Compute magnitude spectrum via simple DFT (for 512-point window). */
    private fun computeMagnitudeSpectrum(windowed: FloatArray): FloatArray {
        val halfSize = FFT_SIZE / 2 + 1
        val magnitudes = FloatArray(halfSize)

        for (k in 0 until halfSize) {
            var real = 0f
            var imag = 0f
            for (n in 0 until FFT_SIZE) {
                val angle = -2.0 * kotlin.math.PI * k * n / FFT_SIZE
                real += windowed[n] * kotlin.math.cos(angle).toFloat()
                imag += windowed[n] * kotlin.math.sin(angle).toFloat()
            }
            magnitudes[k] = real * real + imag * imag
        }

        return magnitudes
    }

    /** Apply mel filterbank to magnitude spectrum. */
    private fun computeMelSpectrum(magnitudes: FloatArray): FloatArray {
        val melSpectrum = FloatArray(NUM_MEL_FILTERS)
        for (i in 0 until NUM_MEL_FILTERS) {
            var sum = 0f
            for (j in magnitudes.indices) {
                sum += MEL_FILTERS[i][j] * magnitudes[j]
            }
            melSpectrum[i] = sum
        }
        return melSpectrum
    }
}


class ParakeetTokenizer(modelFile: File) {

    private data class Piece(val id: Int, val score: Float, val type: Int)

    private val vocab = HashMap<String, Piece>(65536)

    /** CTC blank token ID. */
    val blankId: Int

    /** Token for space character (▁ in SentencePiece). */
    val spaceId: Int

    /** Token for unknown/unrecognized characters. */
    val unkId: Int

    init {
        val raw = parseVocab(modelFile.readBytes())
        raw.forEachIndexed { id, (piece, score, type) ->
            vocab[piece] = Piece(id, score, type)
        }

        blankId = vocab["<blank>"]?.id ?: vocab["▁"]?.id ?: 0
        spaceId = vocab["▁"]?.id ?: vocab[" "]?.id ?: 1
        unkId = vocab["<unk>"]?.id ?: vocab["�"]?.id ?: 2
    }

    /**
     * Decode a sequence of CTC token IDs to text using greedy argmax + blank removal.
     *
     * @param tokenIds Array of token IDs from TFLite output
     * @return Decoded text string
     */
    fun decodeCtc(tokenIds: IntArray): String {
        var prevId = -1
        val chars = StringBuilder()

        for (id in tokenIds) {
            // CTC blank removal: skip blanks and consecutive duplicates
            if (id == blankId) continue
            if (id == prevId) continue

            val piece = vocab.entries.firstOrNull { it.value.id == id }?.key ?: ""
            if (piece.isNotEmpty()) {
                // Replace ▁ (SentencePiece space sentinel) with actual space
                val text = piece.replace("▁", " ")
                chars.append(text)
            }
            prevId = id
        }

        return chars.toString().trim()
    }

    // ── Protobuf parser (SentencePiece binary format) ──────────────────────────

    private data class RawPiece(val piece: String, val score: Float, val type: Int)

    private fun parseVocab(data: ByteArray): List<RawPiece> {
        val result = mutableListOf<RawPiece>()
        var pos = 0

        // Skip protobuf header: magic bytes + version varint
        if (data.size > 4) {
            val (magic, next) = readVarint(data, 0)
            pos = next
        }

        // Parse fields — vocab entries are field 2 (length-delimited)
        while (pos < data.size) {
            val (field, wireType) = readTag(data, pos)
            pos++

            if (field != 2) {
                // Skip non-vocab fields
                pos = skipField(data, pos, wireType)
                continue
            }

            // Field 2: vocab entry — contains piece string, score, type
            // FIX: readVocabEntry returns Triple<Triple<...>, Int, Int>, not 4-tuple
            val entry = readVocabEntry(data, pos)
            val (piece, score, type) = entry.first
            val next = entry.second
            result.add(RawPiece(piece, score, type))
            pos = next
        }

        return result
    }

    private fun readTag(data: ByteArray, pos: Int): Pair<Int, Int> {
        val byte = data[pos].toInt() and 0xFF
        return Pair(byte shr 3, byte and 7)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            shift += 7
            pos++
            if ((byte and 0x80) == 0) break
        }
        return Pair(result, pos)
    }

    private fun readPackedString(data: ByteArray, pos: Int): Pair<String, Int> {
        val (len, next) = readVarint(data, pos)
        val bytes = data.copyOfRange(next, next + len.toInt())
        return Pair(bytes.decodeToString(), next + len.toInt())
    }

    private fun readVocabEntry(data: ByteArray, pos: Int): Triple<Triple<String, Float, Int>, Int, Int> {
        // Vocab entries contain nested sub-fields: piece (string), score (float), type (int)
        var offset = pos
        var piece = ""
        var score = 0f
        var type = 0

        // First sub-field: piece string
        val (pieceStr, next1) = readPackedString(data, offset)
        piece = pieceStr
        offset = next1

        // Second sub-field: score (float = 4 bytes, wire type 5)
        if (offset + 4 <= data.size) {
            score = java.nio.ByteBuffer.wrap(data, offset, 4).float
            offset += 4
        }

        // Third sub-field: type (varint = wire type 0)
        if (offset < data.size) {
            val (typeVal, next2) = readVarint(data, offset)
            type = typeVal.toInt()
            offset = next2
        }

        return Triple(Triple(piece, score, type), offset, offset)
    }

    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> { // Varint
                var p = pos
                while (p < data.size && (data[p].toInt() and 0x80) != 0) p++
                p + 1
            }
            1 -> { // 64-bit
                pos + 8
            }
            2 -> { // Length-delimited
                val (len, next) = readVarint(data, pos)
                next + len.toInt()
            }
            5 -> { // 32-bit
                pos + 4
            }
            else -> pos + 1
        }
    }

    companion object {
        private const val TYPE_UNUSED = 5
    }
}
