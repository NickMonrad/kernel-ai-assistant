package com.kernel.ai.core.voice.asr4all

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo

/**
 * The token-space second pass for punctuation, capitalisation and error correction. Port of
 * `_Pcec` in FUTO's reference driver, with the word-spotter `keep` path removed: `keep` is fed
 * zeros, which is what the reference does when no biasing decoder is set.
 *
 * Feeds a delay line of [commit]-token blocks through the stream graph; [lookahead] is the model's
 * settle lag in tokens, read from the flush graph's output width.
 */
internal class Asr4allPcec(
    private val env: OrtEnvironment,
    private val meta: Asr4allMetadata,
    private val stream: OrtSession,
    private val flush: OrtSession,
    private val p: String,
    private val dim: Int,
    val commit: Int,
    val lookahead: Int,
) {
    /** Rendered slot ids accumulated across committed blocks. */
    val slots = ArrayList<Int>()

    private val tokIds = ArrayList<Int>()
    private val pauseBuckets = ArrayList<Int>()
    private val acoustic = ArrayList<FloatArray>()
    private var consumed = 0
    private var open: OpenToken? = null
    private var blanks = 0
    private var prev = -1
    private var carry: OrtSession.Result? = null

    private class OpenToken(val id: Int, val hiddenSum: DoubleArray, var frames: Int, val leadingBlanks: Int)

    init {
        reset()
    }

    fun reset() {
        carry?.close()
        carry = null
        tokIds.clear()
        pauseBuckets.clear()
        acoustic.clear()
        slots.clear()
        consumed = 0
        open = null
        blanks = 0
        prev = -1
    }

    fun close() {
        carry?.close()
        carry = null
    }

    /** One committed chunk of frames -> pooled tokens. [hidden] is row-major [frames, dim]. */
    fun pool(frameIds: IntArray, hidden: FloatArray) {
        for (t in frameIds.indices) {
            val fid = frameIds[t]
            if (fid != prev && fid != meta.blankId) {
                // A new token starts here.
                open?.let(::emit)
                open = OpenToken(fid, DoubleArray(dim) { d -> hidden[t * dim + d].toDouble() }, 1, blanks)
                blanks = 0
            } else {
                // Repeats and blanks belong to the open token.
                open?.let { o ->
                    for (d in 0 until dim) o.hiddenSum[d] += hidden[t * dim + d]
                    o.frames += 1
                }
            }
            prev = fid
            if (fid == meta.blankId) blanks += 1
        }
    }

    /** Closes the token still held back. */
    fun flush() {
        open?.let(::emit)
    }

    private fun emit(o: OpenToken) {
        open = null
        tokIds += o.id
        acoustic += FloatArray(dim) { d -> (o.hiddenSum[d] / o.frames).toFloat() }
        pauseBuckets += pauseBucket(o.leadingBlanks)
    }

    /** Runs every whole commit that is ready; caches remain in the ORT result. */
    fun drain() {
        while (tokIds.size >= commit) {
            val out = runGraph(stream, listOf("${p}_slots", "${p}_state_out", "${p}_seen_out"), commit, commit, false)
            // The delay line holds lookahead tokens; omit warm-up slots until that prefix is consumed.
            val skip = minOf(2 * commit, maxOf(0, 2 * lookahead - 2 * consumed))
            val s = out[0]
            for (i in skip until 2 * commit) slots += s[i].toInt()
            repeat(commit) {
                tokIds.removeAt(0)
                pauseBuckets.removeAt(0)
                acoustic.removeAt(0)
            }
            consumed += commit
        }
    }

    /** The last partial commit plus whatever is still inside the model. */
    fun tail(): List<Int> {
        val n = tokIds.size
        val flat = runGraph(flush, listOf("${p}_tail_slots"), commit, n, true).single()
        val valid = minOf(flat.size, 2 * lookahead + 2 * n + 1)
        val skip = maxOf(0, 2 * lookahead - 2 * consumed)
        return if (skip >= valid) emptyList() else (skip until valid).map { flat[it].toInt() }
    }

    fun render(slotIds: List<Int>): String = renderSlots(meta, slotIds)

    private fun runGraph(
        session: OrtSession,
        outputs: List<String>,
        k: Int,
        n: Int,
        flushed: Boolean,
    ): List<LongArray> {
        val carryBefore = carry
        val borrowedState = carryBefore?.let { it.get("${p}_state_out").orElseThrow() as OnnxTensor }
        val borrowedSeen = carryBefore?.let { it.get("${p}_seen_out").orElseThrow() as OnnxTensor }
        val feeds = feeds(session, k, n, carryBefore == null)
        var nReal: OnnxTensor? = null
        var next: OrtSession.Result? = null
        try {
            if (borrowedState != null) feeds["${p}_state"] = borrowedState
            if (borrowedSeen != null) feeds["${p}_seen"] = borrowedSeen
            if (flushed) {
                nReal = env.longs(longArrayOf(n.toLong()), LongArray(0))
                feeds["${p}_n_real"] = nReal
            }
            next = session.run(feeds, outputs.toSet())
            val slotsTensor = next.get(outputs.first()).orElseThrow() as OnnxTensor
            check(slotsTensor.info.type == OnnxJavaType.INT64) {
                "unsupported PCEC slots output type ${slotsTensor.info.type} for ${outputs.first()}"
            }
            val slotBuffer = slotsTensor.longBuffer
            val result = LongArray(slotBuffer.remaining()).also(slotBuffer::get)
            if (!flushed) {
                carry = next
                next = null
                carryBefore?.close()
            }
            return listOf(result)
        } finally {
            feeds.values.forEach { tensor ->
                if (tensor !== borrowedState && tensor !== borrowedSeen && tensor !== nReal) {
                    runCatching { tensor.close() }
                }
            }
            nReal?.close()
            next?.close()
        }
    }

    private fun feeds(
        session: OrtSession,
        k: Int,
        n: Int,
        includeInitialCaches: Boolean,
    ): MutableMap<String, OnnxTensor> {
        val ids = LongArray(k) { meta.padIn }
        val pause = LongArray(k) { meta.pauseBlackout }
        val ac = FloatArray(k * dim)
        for (i in 0 until n) {
            ids[i] = tokIds[i].toLong()
            // A model trained with the pause channel blacked out must keep receiving that value.
            if (meta.realPause) pause[i] = pauseBuckets[i].toLong()
            acoustic[i].copyInto(ac, i * dim)
        }
        val feeds = mutableMapOf<String, OnnxTensor>()
        val owned = ArrayList<OnnxTensor>(if (includeInitialCaches) 6 else 4)
        try {
            fun own(name: String, create: () -> OnnxTensor) {
                val tensor = create()
                owned += tensor
                feeds[name] = tensor
            }
            own("${p}_hyp_ids") { env.longs(ids, longArrayOf(1, k.toLong())) }
            own("${p}_pause") { env.longs(pause, longArrayOf(1, k.toLong())) }
            own("${p}_acoustic") { env.floats(ac, longArrayOf(1, k.toLong(), dim.toLong())) }
            own("${p}_keep") { env.floats(FloatArray(k), longArrayOf(1, k.toLong())) }
            if (includeInitialCaches) {
                own("${p}_state") { zeroInput(session, "${p}_state") }
                own("${p}_seen") { zeroInput(session, "${p}_seen") }
            }
            return feeds
        } catch (t: Throwable) {
            owned.forEach { runCatching { it.close() } }
            throw t
        }
    }

    private fun zeroInput(session: OrtSession, name: String): OnnxTensor {
        val info = session.inputInfo.getValue(name).info as TensorInfo
        val shape = info.shape
        val count = shape.fold(1L) { acc, size -> acc * size }.toInt()
        return when (info.type) {
            OnnxJavaType.FLOAT -> env.floats(FloatArray(count), shape)
            OnnxJavaType.INT64 -> env.longs(LongArray(count), shape)
            else -> error("unsupported PCEC input type ${info.type} for $name")
        }
    }


    private fun pauseBucket(blanks: Int): Int = PAUSE_THRESHOLDS.count { blanks >= it }

    private companion object {
        /** Blank-frame counts separating the pause buckets PCEC was trained on. */
        val PAUSE_THRESHOLDS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)
    }
}
