package com.kernel.ai.core.voice.asr4all

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.JsonParser
import java.io.File

/**
 * Kotlin port of FUTO's asr4all ONNX streaming driver (`onnx/asr4all_onnx.py`): one tier's
 * streaming graph plus its PCEC punctuation/casing rung, decoding raw 16 kHz mono float PCM.
 *
 * Behaviour matches the reference with PCEC on and no word-spotter biasing (the spotter module is
 * not published, so PCEC's `keep` input is fed zeros, as the reference does without biasing).
 * Shared weights are mapped once per engine from `asr4all.onnx.data` and handed to every session
 * as external initializers, so every graph references the same bytes.
 *
 * Not thread-safe: one owner drives an engine. [close] releases every session and tensor; any
 * call after close throws.
 */
class Asr4allEngine private constructor(
    private val env: OrtEnvironment,
    private val meta: Asr4allMetadata,
    private val tierName: String,
    private val window: Int,
    private val commit: Int,
    private val dim: Int,
    private val spf: Int,
    private val stream: OrtSession,
    private val pcec: Asr4allPcec?,
    private val owned: List<AutoCloseable>,
) : AutoCloseable {

    private val windowFrames get() = window / spf
    private var closed = false
    private var filterCache = FloatArray(0)
    private var attnCache = FloatArray(0)
    private var pcm = FloatArray(0)
    private var pcmLen = 0
    private var nextFrame = 0
    private val ids = ArrayList<Int>()

    init {
        reset()
    }

    /** Feeds audio, commits every complete window, returns the transcript so far (no punctuation). */
    fun decodeStream(chunk: FloatArray): String {
        ensureOpen()
        appendPcm(chunk)
        while ((nextFrame + windowFrames) * spf <= pcmLen) step()
        return text()
    }

    /** Flushes the tail, zero-padding the missing lookahead, and closes PCEC. */
    fun finish(): String {
        ensureOpen()
        while (nextFrame * spf < pcmLen) step()
        pcec?.flush()
        pcec?.drain()
        return text()
    }

    /** Punctuated, cased text. It trails the acoustic transcript; [decodeTail] also renders what is still inside the model. */
    fun decodePcec(decodeTail: Boolean = false): String {
        ensureOpen()
        val p = pcec ?: return text()
        val slots = p.slots + (if (decodeTail) p.tail() else emptyList())
        return p.render(slots)
    }

    /** A whole clip: the streaming loop run to completion. */
    fun decodeOffline(clip: FloatArray, applyPcec: Boolean = true): String {
        reset()
        decodeStream(clip)
        finish()
        return if (applyPcec && pcec != null) decodePcec(decodeTail = true) else text()
    }

    /** Starts a new utterance. Zero caches are the initialised state. */
    fun reset() {
        ensureOpen()
        filterCache = FloatArray(stream.inputShape("filter_cache")[0].toInt())
        attnCache = FloatArray(stream.inputShape("attn_cache")[0].toInt())
        pcm = FloatArray(0)
        pcmLen = 0
        nextFrame = 0
        ids.clear()
        pcec?.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        owned.asReversed().forEach { runCatching { it.close() } }
    }

    val isClosed: Boolean get() = closed

    private fun ensureOpen() = check(!closed) { "Asr4allEngine($tierName) is closed" }

    private fun appendPcm(chunk: FloatArray) {
        if (pcmLen + chunk.size > pcm.size) {
            pcm = pcm.copyOf(maxOf(pcm.size * 2, pcmLen + chunk.size))
        }
        chunk.copyInto(pcm, pcmLen)
        pcmLen += chunk.size
    }

    private fun step() {
        val start = nextFrame * spf
        val win = FloatArray(window)
        val hi = minOf(pcmLen, start + window)
        if (hi > start) pcm.copyInto(win, 0, start, hi)

        val withHidden = pcec != null
        val outputs = buildList {
            add("${tierName}_log_probs")
            add("${tierName}_filter_cache_out")
            add("${tierName}_attn_cache_out")
            if (withHidden) add("${tierName}_hidden")
        }
        val out = stream.runCopy(
            mapOf(
                "${tierName}_pcm" to env.floats(win, longArrayOf(1, window.toLong())),
                "filter_cache" to env.floats(filterCache, longArrayOf(filterCache.size.toLong())),
                "attn_cache" to env.floats(attnCache, longArrayOf(attnCache.size.toLong())),
            ),
            outputs,
        )
        filterCache = out.floats("${tierName}_filter_cache_out")
        attnCache = out.floats("${tierName}_attn_cache_out")

        val valid = minOf(commit, pcmLen / spf - nextFrame)
        val logProbs = out.floats("${tierName}_log_probs")
        val vocab = logProbs.size / commit
        val frameIds = IntArray(valid) { t -> argmax(logProbs, t * vocab, vocab) }
        val hidden = if (withHidden) out.floats("${tierName}_hidden") else null
        commit(frameIds, hidden)
        nextFrame += commit
    }

    /** Frames that are final: into the transcript, and into PCEC. */
    private fun commit(frameIds: IntArray, hidden: FloatArray?) {
        for (id in frameIds) ids += id
        val p = pcec ?: return
        if (hidden != null && frameIds.isNotEmpty()) {
            p.pool(frameIds, hidden)
            p.drain()
        }
    }

    /** CTC collapse of the accumulated frame ids: drop repeats and blanks. */
    private fun text(): String {
        val out = StringBuilder()
        var prev = -1
        for (i in ids) {
            if (i != prev && i != meta.blankId && i < meta.vocab.size) out.append(meta.vocab[i])
            prev = i
        }
        return out.toString().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Opens [tierName] (default: the metadata's `default_tier`) from an asr4all release `onnx/`
         * directory. Only that tier's streaming graph and its PCEC rung are loaded; the shared weights
         * are mapped once and referenced by those graphs.
         */
        fun open(
            onnxDir: File,
            tierName: String? = null,
            env: OrtEnvironment = OrtEnvironment.getEnvironment(),
        ): Asr4allEngine {
            val meta = Asr4allMetadata(JsonParser.parseString(File(onnxDir, "metadata.json").readText()).asJsonObject)
            val tier = tierName ?: meta.defaultTier
            require(tier in meta.tierNames) { "unknown asr4all tier $tier; have ${meta.tierNames}" }
            val rung = meta.tierRung[tier]?.let { meta.rungs.getValue(it) }

            val owned = mutableListOf<AutoCloseable>()
            try {
                val graphFiles = listOfNotNull(
                    meta.graphs.getValue(tier),
                    rung?.name?.let { meta.graphs.getValue(it) },
                    rung?.flush?.let { meta.graphs.getValue(it) },
                ).distinct()
                val weights = loadWeights(env, onnxDir, meta, graphFiles, owned)

                fun session(graph: String): OrtSession =
                    openSession(env, File(onnxDir, graph), weights.getValue(graph)).also { owned += it }

                val stream = session(meta.graphs.getValue(tier))
                val window = stream.inputShape("${tier}_pcm").last().toInt()
                val commit = stream.outputShape("${tier}_log_probs")[1].toInt()
                val hasHidden = stream.outputInfo.containsKey("${tier}_hidden")
                val dim = if (hasHidden) stream.outputShape("${tier}_hidden").last().toInt() else 0

                val pcec = rung?.let { r ->
                    require(hasHidden) { "asr4all tier $tier has PCEC rung ${r.name} but no hidden output" }
                    val s = session(meta.graphs.getValue(r.name))
                    val f = session(meta.graphs.getValue(r.flush))
                    val pcecCommit = s.inputShape("${r.name}_hyp_ids")[1].toInt()
                    val flushSlots = f.outputShape("${r.name}_tail_slots")[1].toInt()
                    Asr4allPcec(
                        env = env,
                        meta = meta,
                        stream = s,
                        flush = f,
                        p = r.name,
                        dim = dim,
                        commit = pcecCommit,
                        lookahead = (flushSlots - 1) / 2 - pcecCommit,
                    )
                }
                return Asr4allEngine(
                    env = env,
                    meta = meta,
                    tierName = tier,
                    window = window,
                    commit = commit,
                    dim = dim,
                    spf = meta.samplesPerFrame,
                    stream = stream,
                    pcec = pcec,
                    owned = owned,
                )
            } catch (t: Throwable) {
                owned.asReversed().forEach { runCatching { it.close() } }
                throw t
            }
        }

        /**
         * Maps each distinct (offset, length) region of the shared data file once. Returns, per graph
         * file, the initializer tensors that graph names. Tensors are registered in [owned].
         */
        private fun loadWeights(
            env: OrtEnvironment,
            onnxDir: File,
            meta: Asr4allMetadata,
            graphFiles: List<String>,
            owned: MutableList<AutoCloseable>,
        ): Map<String, Map<String, OnnxTensor>> {
            val weightsName = meta.weightsFile
            val regions = HashMap<Pair<Long, Long>, OnnxTensor>()
            return graphFiles.associateWith { file ->
                val refs = mutableMapOf<String, OnnxTensor>()
                if (weightsName != null) {
                    for (t in Asr4allExternalData.externalTensors(File(onnxDir, file).readBytes())) {
                        val tensor = regions.getOrPut(t.offset to t.length) {
                            val buf = Asr4allExternalData.mapRegion(File(onnxDir, weightsName), t.offset, t.length)
                            OnnxTensor.createTensor(env, buf, t.dims, onnxJavaType(t.onnxDataType)).also { owned += it }
                        }
                        refs[t.name] = tensor
                    }
                }
                refs
            }
        }

        private fun onnxJavaType(onnxDataType: Int): OnnxJavaType = when (onnxDataType) {
            1 -> OnnxJavaType.FLOAT
            6 -> OnnxJavaType.INT32
            7 -> OnnxJavaType.INT64
            9 -> OnnxJavaType.BOOL
            10 -> OnnxJavaType.FLOAT16
            11 -> OnnxJavaType.DOUBLE
            else -> error("unsupported asr4all initializer dtype $onnxDataType")
        }

        private fun openSession(env: OrtEnvironment, file: File, weights: Map<String, OnnxTensor>): OrtSession {
            OrtSession.SessionOptions().use { so ->
                so.setCPUArenaAllocator(false)
                so.addConfigEntry("session.disable_prepacking", "1")
                so.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                if (weights.isNotEmpty()) so.addExternalInitializers(weights)
                return env.createSession(file.absolutePath, so)
            }
        }

        private fun argmax(values: FloatArray, offset: Int, size: Int): Int {
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (i in 0 until size) {
                val v = values[offset + i]
                if (v > bestValue) {
                    bestValue = v
                    best = i
                }
            }
            return best
        }
    }
}

/**
 * Port of `render_slots`: lattice slots -> punctuated, cased text. CTC collapse, control tokens
 * for capitalisation and punctuation, then sentence-start capitalisation.
 */
internal fun renderSlots(meta: Asr4allMetadata, slots: List<Int>): String {
    val vocab = meta.pcecVocab
    val blank = meta.pcecBlankId
    val ctrl = meta.ctrl
    val case = mapOf(ctrl.cap to "cap", ctrl.allcaps to "upper")
    val punct = mapOf(ctrl.period to ".", ctrl.comma to ",", ctrl.question to "?")

    // each word: pieces, case, trailing punctuation
    class Word(val pieces: MutableList<String>, val case: String, var punct: String)
    val words = mutableListOf<Word>()
    var pending = ""
    var prev = -1
    for (i in slots) {
        if (i == prev || i == blank) {
            prev = i
            continue
        }
        prev = i
        val c = case[i]
        val pnc = punct[i]
        if (c != null) {
            pending = c
        } else if (pnc != null) {
            if (words.isNotEmpty()) words.last().punct = pnc
        } else if (i < vocab.size && vocab[i].isNotEmpty()) {
            if (vocab[i].startsWith(" ") || words.isEmpty()) {
                words += Word(mutableListOf(vocab[i]), pending, "")
                pending = ""
            } else {
                words.last().pieces += vocab[i]
            }
        }
    }

    val out = mutableListOf<String>()
    var sentenceStart = true
    for (word in words) {
        var w = word.pieces.joinToString("").trim()
        if (w.isEmpty()) continue
        w = when {
            word.case == "upper" -> w.uppercase()
            word.case == "cap" || sentenceStart -> w.substring(0, 1).uppercase() + w.substring(1)
            else -> w
        }
        sentenceStart = word.punct == "." || word.punct == "?"
        out += w + word.punct
    }
    return out.joinToString(" ")
}
