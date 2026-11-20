package ai.jandal.spike1988

import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Benchmark-only Kotlin port of FUTO's published `executorch/asr4all_executorch.py` streaming driver
 * (pinned revisions in [Asr4allEtGeometry]): stream tier + `pcec_c16`/`pcec_c16_flush`, no word spotter.
 * Mirrors the reference step for step: the window starts at the commit boundary and is zero-padded,
 * caches are copied out of the method arena after every call, PCEC pools committed frames into tokens.
 */
class EtAsr4allDecoder(
    private val module: Module,
    metadata: JSONObject,
    size: String,
    private val tier: String,
) {
    private val geo = Asr4allEtGeometry.of(size, tier)
    private val spf = metadata.getInt("samples_per_frame")
    private val blank = metadata.getInt("blank_id")
    private val vocab = metadata.getJSONArray("vocab").let { a -> Array(a.length()) { a.getString(it) } }
    private val pcecMeta = metadata.getJSONObject("pcec")
    private val pcecVocab = pcecMeta.getJSONArray("vocab").let { a -> Array(a.length()) { a.getString(it) } }
    private val pcecBlank = pcecMeta.getInt("blank_id")
    private val padIn = pcecMeta.getInt("pad_in").toLong()
    private val pauseBlackout = pcecMeta.getInt("pause_blackout").toLong()
    private val realPause = pcecMeta.optBoolean("real_pause", false)
    private val ctrl = pcecMeta.getJSONObject("ctrl")
    private val caseCtl = mapOf(ctrl.getInt("cap") to "cap", ctrl.getInt("allcaps") to "upper")
    private val punctCtl = mapOf(ctrl.getInt("period") to ".", ctrl.getInt("comma") to ",", ctrl.getInt("question") to "?")

    private val pcecMethod: String = metadata.getJSONArray("tiers").let { tiers ->
        (0 until tiers.length()).map(tiers::getJSONObject).single { it.getString("name") == tier }.getJSONObject("pcec")
    }.let { require(it.optInt("cache_tensors", 2) == 2); it.getString("name") }
    private val pcecFlushMethod: String = metadata.getJSONArray("tiers").let { tiers ->
        (0 until tiers.length()).map(tiers::getJSONObject).single { it.getString("name") == tier }.getJSONObject("pcec")
    }.getString("flush")
    private val logits = directFloats(geo.commit * geo.vocab)
    private val hidden = directFloats(geo.commit * geo.hidden)

    // Stream state. Caches live in reusable direct buffers (the method copies them into its planned inputs).
    private val window = directFloats(geo.window)
    private val filter = directFloats(geo.filter)
    private val attn = directFloats(geo.attn)
    private val windowShape = longArrayOf(1, geo.window.toLong())
    private val filterShape = longArrayOf(geo.filter.toLong())
    private val attnShape = longArrayOf(geo.attn.toLong())
    private var pcm = FloatArray(16_000 * 8)
    private var pcmLen = 0
    private var nextFrame = 0
    private val ids = ArrayList<Int>()

    // PCEC state (reference `_Pcec`).
    private val pcecCommit = PCEC_COMMIT
    private val lookahead = (PCEC_FLUSH_SLOTS - 1) / 2 - pcecCommit
    private val pcecCache = directFloats(PCEC_CACHE)
    private var pcecSeen = 0L
    private val tokIds = ArrayList<Int>()
    private val tokPause = ArrayList<Int>()
    private val tokAcoustic = ArrayList<FloatArray>()
    private val slots = ArrayList<Int>()
    private var consumed = 0
    private var openId = -1
    private var openSum: DoubleArray? = null
    private var openFrames = 0
    private var openLead = 0
    private var blanks = 0
    private var prev = -1

    /** Number of stream/PCEC method calls, for diagnostics. */
    var streamCalls = 0; private set
    var pcecCalls = 0; private set

    /** Diagnostic: when set, receives top-1 minus top-2 log-prob for every committed frame. */
    var frameMargins: MutableList<Float>? = null

    /** Committed CTC frame ids so far (diagnostic parity). */
    val frameIds: List<Int> get() = ids

    fun reset() {
        filter.clear(); for (i in 0 until geo.filter) filter.put(i, 0f)
        attn.clear(); for (i in 0 until geo.attn) attn.put(i, 0f)
        pcmLen = 0; nextFrame = 0; ids.clear()
        for (i in 0 until PCEC_CACHE) pcecCache.put(i, 0f)
        pcecSeen = 0; tokIds.clear(); tokPause.clear(); tokAcoustic.clear(); slots.clear()
        consumed = 0; openSum = null; openId = -1; openFrames = 0; openLead = 0; blanks = 0; prev = -1
    }

    /** Reference `decode_stream`: feed audio, run every complete window, return the raw transcript. */
    fun decodeStream(chunk: FloatArray, length: Int = chunk.size): String {
        if (pcmLen + length > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, pcmLen + length))
        chunk.copyInto(pcm, pcmLen, 0, length)
        pcmLen += length
        val windowFrames = geo.window / spf
        while ((nextFrame + windowFrames) * spf <= pcmLen) step()
        return text()
    }

    /** Reference `finish`: flush the zero-padded tail, close and drain PCEC. */
    fun finish(): String {
        while (nextFrame * spf < pcmLen) step()
        closeOpen()
        drain()
        return text()
    }

    /** Reference `decode_pcec`. */
    fun decodePcec(decodeTail: Boolean): String = render(if (decodeTail) slots + tail() else slots)

    fun text(): String {
        val sb = StringBuilder()
        var p = -1
        for (i in ids) {
            if (i != p && i != blank && i < vocab.size) sb.append(vocab[i])
            p = i
        }
        return sb.split(WS).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun step() {
        val start = nextFrame * spf
        val hi = minOf(pcmLen, start + geo.window)
        window.clear()
        for (i in 0 until geo.window) window.put(i, if (start + i < hi) pcm[start + i] else 0f)
        val outs = module.execute(
            tier,
            EValue.from(Tensor.fromBlob(window, windowShape)),
            EValue.from(Tensor.fromBlob(filter, filterShape)),
            EValue.from(Tensor.fromBlob(attn, attnShape)),
        )
        streamCalls++
        check(outs.size == 4) { "$tier returned ${outs.size} outputs" }
        // Outputs live in the method arena: copy the caches before the next call.
        filter.clear(); outs[1].toTensor().copyDataInto(filter); filter.clear()
        attn.clear(); outs[2].toTensor().copyDataInto(attn); attn.clear()
        logits.clear(); outs[0].toTensor().copyDataInto(logits); logits.clear()
        hidden.clear(); outs[3].toTensor().copyDataInto(hidden); hidden.clear()
        val valid = minOf(geo.commit, pcmLen / spf - nextFrame)
        val vocabN = geo.vocab
        for (t in 0 until valid) {
            var best = 0
            var bestScore = logits.get(t * vocabN)
            var second = Float.NEGATIVE_INFINITY
            for (v in 1 until vocabN) {
                val s = logits.get(t * vocabN + v)
                if (s > bestScore) { second = bestScore; bestScore = s; best = v } else if (s > second) second = s
            }
            frameMargins?.add(bestScore - second)
            ids += best
            pool(best, hidden, t * geo.hidden)
        }
        if (valid > 0) drain()
        nextFrame += geo.commit
    }

    private fun pool(fid: Int, hidden: FloatBuffer, offset: Int) {
        if (fid != prev && fid != blank) {
            if (openSum != null) emit()
            openSum = DoubleArray(geo.hidden) { hidden.get(offset + it).toDouble() }
            openId = fid; openFrames = 1; openLead = blanks
            blanks = 0
        } else {
            val sum = openSum
            if (sum != null) {
                for (i in sum.indices) sum[i] += hidden.get(offset + i)
                openFrames++
            }
        }
        prev = fid
        if (fid == blank) blanks++
    }

    private fun closeOpen() { if (openSum != null) emit() }

    private fun emit() {
        val sum = openSum!!
        tokIds += openId
        tokAcoustic += FloatArray(sum.size) { (sum[it] / openFrames).toFloat() }
        tokPause += PAUSE_THRESHOLDS.count { openLead >= it }
        openSum = null
    }

    private fun drain() {
        val k = pcecCommit
        while (tokIds.size >= k) {
            val outs = module.execute(pcecMethod, *(feats(k, k) + caches()))
            pcecCalls++
            check(outs.size == 3) { "$pcecMethod returned ${outs.size} outputs" }
            val out = outs[0].toTensor().dataAsLongArray
            val skip = minOf(2 * k, maxOf(0, 2 * lookahead - 2 * consumed))
            for (i in skip until 2 * k) slots += out[i].toInt()
            pcecCache.clear(); outs[1].toTensor().copyDataInto(pcecCache); pcecCache.clear()
            pcecSeen = outs[2].toTensor().dataAsLongArray[0]
            repeat(k) { tokIds.removeAt(0); tokPause.removeAt(0); tokAcoustic.removeAt(0) }
            consumed += k
        }
    }

    private fun tail(): List<Int> {
        val n = tokIds.size
        val inputs = feats(pcecCommit, n) + EValue.from(Tensor.fromBlob(longArrayOf(n.toLong()), longArrayOf())) + caches()
        val out = module.execute(pcecFlushMethod, *inputs).also { pcecCalls++ }[0].toTensor().dataAsLongArray
        val valid = 2 * lookahead + 2 * n + 1
        val skip = maxOf(0, 2 * lookahead - 2 * consumed)
        return out.copyOfRange(skip, valid).map { it.toInt() }
    }

    private fun caches(): Array<EValue> = arrayOf(
        EValue.from(Tensor.fromBlob(pcecCache, longArrayOf(1, PCEC_CACHE.toLong()))),
        EValue.from(Tensor.fromBlob(longArrayOf(pcecSeen), longArrayOf(1))),
    )

    private fun feats(k: Int, n: Int): Array<EValue> {
        val tok = LongArray(k) { padIn }
        val pause = LongArray(k) { pauseBlackout }
        val ac = FloatArray(k * geo.hidden)
        val keep = FloatArray(k) // no word spotter: keep = 0 everywhere, exactly as the reference CLI
        for (i in 0 until n) {
            tok[i] = tokIds[i].toLong()
            if (realPause) pause[i] = tokPause[i].toLong()
            tokAcoustic[i].copyInto(ac, i * geo.hidden)
        }
        return arrayOf(
            EValue.from(Tensor.fromBlob(tok, longArrayOf(1, k.toLong()))),
            EValue.from(Tensor.fromBlob(pause, longArrayOf(1, k.toLong()))),
            EValue.from(Tensor.fromBlob(ac, longArrayOf(1, k.toLong(), geo.hidden.toLong()))),
            EValue.from(Tensor.fromBlob(keep, longArrayOf(1, k.toLong()))),
        )
    }

    private fun render(slotIds: List<Int>): String {
        class Word(val pieces: StringBuilder, val case: String, var punct: String = "")
        val words = ArrayList<Word>()
        var pending = ""
        var p = -1
        for (i in slotIds) {
            if (i == p || i == pcecBlank) { p = i; continue }
            p = i
            when {
                i in caseCtl -> pending = caseCtl.getValue(i)
                i in punctCtl -> if (words.isNotEmpty()) words.last().punct = punctCtl.getValue(i)
                i < pcecVocab.size && pcecVocab[i].isNotEmpty() -> {
                    val piece = pcecVocab[i]
                    if (piece.startsWith(" ") || words.isEmpty()) {
                        words += Word(StringBuilder(piece), pending); pending = ""
                    } else {
                        words.last().pieces.append(piece)
                    }
                }
            }
        }
        val out = ArrayList<String>()
        var sentenceStart = true
        for (w in words) {
            var s = w.pieces.toString().trim()
            if (s.isEmpty()) continue
            s = when {
                w.case == "upper" -> s.uppercase(java.util.Locale.ROOT)
                w.case == "cap" || sentenceStart -> s.substring(0, 1).uppercase(java.util.Locale.ROOT) + s.substring(1)
                else -> s
            }
            sentenceStart = w.punct == "." || w.punct == "?"
            out += s + w.punct
        }
        return out.joinToString(" ")
    }

    companion object {
        private val PAUSE_THRESHOLDS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)
        private val WS = Regex("\\s+")
        const val PCEC_COMMIT = 16
        const val PCEC_FLUSH_SLOTS = 65
        const val PCEC_CACHE = 166_976

        private fun directFloats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}

private operator fun Array<EValue>.plus(other: EValue): Array<EValue> = arrayOf(*this, other)
private operator fun Array<EValue>.plus(other: Array<EValue>): Array<EValue> = arrayOf(*this, *other)
