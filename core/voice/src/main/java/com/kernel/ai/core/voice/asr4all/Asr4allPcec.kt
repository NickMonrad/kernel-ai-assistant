package com.kernel.ai.core.voice.asr4all

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

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

    private var state = FloatArray(0)
    private var seen = LongArray(1)
    private val tokIds = ArrayList<Int>()
    private val pauseBuckets = ArrayList<Int>()
    private val acoustic = ArrayList<FloatArray>()
    private var consumed = 0
    private var open: OpenToken? = null
    private var blanks = 0
    private var prev = -1

    private class OpenToken(val id: Int, val hiddenSum: DoubleArray, var frames: Int, val leadingBlanks: Int)

    init {
        reset()
    }

    fun reset() {
        state = FloatArray(stream.inputShape("${p}_state")[1].toInt())
        seen = LongArray(1)
        tokIds.clear()
        pauseBuckets.clear()
        acoustic.clear()
        slots.clear()
        consumed = 0
        open = null
        blanks = 0
        prev = -1
    }

    /** One committed chunk of frames -> pooled tokens. [hidden] is row-major [frames, dim]. */
    fun pool(frameIds: IntArray, hidden: FloatArray) {
        for (t in frameIds.indices) {
            val fid = frameIds[t]
            if (fid != prev && fid != meta.blankId) { // a new token starts here
                open?.let(::emit)
                open = OpenToken(fid, DoubleArray(dim) { d -> hidden[t * dim + d].toDouble() }, 1, blanks)
                blanks = 0
            } else {
                // a repeat or a blank belongs to the open token
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

    /** Runs every whole commit that is ready; leftovers stay buffered. */
    fun drain() {
        while (tokIds.size >= commit) {
            val out = runGraph(stream, listOf("${p}_slots", "${p}_state_out", "${p}_seen_out"), commit, commit, flushed = false)
            // The delay line holds `lookahead` tokens; until that many have gone through it,
            // its head is still warm-up from before the utterance. Drop those slots.
            val skip = minOf(2 * commit, maxOf(0, 2 * lookahead - 2 * consumed))
            val s = out.longs("${p}_slots")
            for (i in skip until 2 * commit) slots += s[i].toInt()
            state = out.floats("${p}_state_out")
            seen = out.longs("${p}_seen_out")
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
        val out = runGraph(flush, listOf("${p}_tail_slots"), commit, n, flushed = true)
        val flat = out.longs("${p}_tail_slots")
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
    ): Map<String, Any> {
        val inputs = feeds(k, n).toMutableMap()
        if (flushed) inputs["${p}_n_real"] = env.longs(longArrayOf(n.toLong()), LongArray(0))
        return session.runCopy(inputs, outputs)
    }

    /** A full block of `k` tokens, padded past the `n` real ones. */
    private fun feeds(k: Int, n: Int): Map<String, OnnxTensor> {
        val ids = LongArray(k) { meta.padIn }
        val pause = LongArray(k) { meta.pauseBlackout }
        val ac = FloatArray(k * dim)
        for (i in 0 until n) {
            ids[i] = tokIds[i].toLong()
            // real_pause is a training decision invisible in the graph: a model trained with the
            // channel blacked out must be fed the blackout value.
            if (meta.realPause) pause[i] = pauseBuckets[i].toLong()
            acoustic[i].copyInto(ac, i * dim)
        }
        val keep = FloatArray(k)
        return mapOf(
            "${p}_hyp_ids" to env.longs(ids, longArrayOf(1, k.toLong())),
            "${p}_pause" to env.longs(pause, longArrayOf(1, k.toLong())),
            "${p}_acoustic" to env.floats(ac, longArrayOf(1, k.toLong(), dim.toLong())),
            "${p}_keep" to env.floats(keep, longArrayOf(1, k.toLong())),
            "${p}_state" to env.floats(state, longArrayOf(1, state.size.toLong())),
            "${p}_seen" to env.longs(seen, longArrayOf(1)),
        )
    }

    private fun pauseBucket(blanks: Int): Int = PAUSE_THRESHOLDS.count { blanks >= it }

    private companion object {
        /** Blank-frame counts separating the pause buckets PCEC was trained on. */
        val PAUSE_THRESHOLDS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)
    }
}
