package com.kernel.ai.core.voice.asr4all

/** One acoustic token as the PCEC head consumes it. */
class PcecToken(
    val id: Int,
    val startFrame: Int,
    var endFrame: Int,
    /** Log2 bucket of the blank frames before the token (see [PcecFeatures.pauseBucket]). */
    val pauseBucket: Int,
    hiddenDim: Int,
) {
    /** Sum of the encoder hidden rows over the token's span, in double precision. */
    private val hiddenSum = DoubleArray(hiddenDim)
    var hiddenFrames: Int = 0
        private set

    fun addHidden(hidden: FloatArray, offset: Int) {
        for (i in hiddenSum.indices) hiddenSum[i] += hidden[offset + i]
        hiddenFrames++
    }

    /** Mean of the encoder hidden rows over the token's span: its run and the blank frames after it. */
    fun pooledHidden(into: FloatArray, offset: Int) {
        val n = hiddenFrames.coerceAtLeast(1)
        for (i in hiddenSum.indices) into[offset + i] = (hiddenSum[i] / n).toFloat()
    }
}

/**
 * Input features for the PCEC head, derived from the CTC timing (CLEANROOM.md, decisions 3 and
 * 7; fixed against the verifier's reference outputs).
 *
 * The pause before a token is the number of blank frames between the previous token's last
 * frame and this token's first frame (for the first token, the frames before it), bucketed on a
 * log2 scale into [Asr4AllPcecConfig.pauseBuckets] buckets: 0 frames → 0, 1 → 1, 2–3 → 2, 4–7 → 3,
 * …, capped at the last bucket.
 */
internal object PcecFeatures {
    fun pauseBucket(pcec: Asr4AllPcecConfig, blankFramesBefore: Int): Int {
        if (blankFramesBefore <= 0) return 0
        val floorLog2 = 31 - Integer.numberOfLeadingZeros(blankFramesBefore)
        return (floorLog2 + 1).coerceAtMost(pcec.pauseBuckets - 1)
    }

    /**
     * The head's per-token float input (its fourth input). Measured: values near 0 leave the
     * words as decoded, negative values make the head rewrite heavily; 0 reproduces the
     * verifier's reference finals on every row (CLEANROOM.md).
     */
    const val FLOAT_INPUT = 0f
}

/**
 * Drives the PCEC head over the token stream. Full rungs of tokens go through the streaming
 * method, which returns the slots of tokens [Asr4AllPcecConfig.lagTokens] behind; those slots are
 * settled. The flush method returns the slots of the held-back tokens plus the pending ones and
 * the end-of-input slot, and gives both the live text and the final text.
 */
internal class PcecStream(
    private val program: Asr4AllProgram,
    private val config: Asr4AllModelConfig,
    private val tier: Asr4AllTier,
) {
    private val pcec = config.pcec
    private val rung = tier.pcecRung
    private val hiddenDim = config.hiddenDim
    private val pending = ArrayDeque<PcecToken>()
    private var state = program.initialPcecState()
    private var seen = 0L
    private var settled = IntArray(256)
    private var settledCount = 0
    private val renderer = PcecTextRenderer(pcec)

    fun add(token: PcecToken) {
        pending.addLast(token)
        while (pending.size >= rung) commitRung()
    }

    /**
     * Settled text plus the flushed tail of the pending tokens, including the end-of-input slot
     * (where the head puts a closing mark). The live text is the same flush over the tokens
     * handed over so far, so it never shows the latest token, whose span is still open.
     */
    fun text(): String {
        val n = pending.size
        check(n < rung) { "PCEC tail $n is not below rung $rung" }
        val flush = program.pcecFlush(encode(pending), n, state, seen)
        val used = pcec.slotsPerToken * (pcec.lagTokens + n) + 1
        val ids = renderer.collapse(IntSlice(settled, 0, settledCount), IntSlice(flush, 0, used))
        return renderer.render(ids)
    }

    fun reset() {
        pending.clear()
        state = program.initialPcecState()
        seen = 0L
        settledCount = 0
    }

    private fun commitRung() {
        val tokens = ArrayList<PcecToken>(rung)
        repeat(rung) { tokens += pending.removeFirst() }
        val out = program.pcecStream(encode(tokens), state, seen)
        state = out.state
        seen = out.seen
        appendSettled(out.slots)
    }

    private fun appendSettled(slots: IntArray) {
        if (settledCount + slots.size > settled.size) {
            settled = settled.copyOf(maxOf(settled.size * 2, settledCount + slots.size))
        }
        System.arraycopy(slots, 0, settled, settledCount, slots.size)
        settledCount += slots.size
    }

    /** Packs [tokens] (at most one rung) into one rung of inputs, padding the rest. */
    private fun encode(tokens: List<PcecToken>): PcecInputs {
        val ids = LongArray(rung) { pcec.padInputId.toLong() }
        val pauses = LongArray(rung)
        val hidden = FloatArray(rung * hiddenDim)
        val floats = FloatArray(rung)
        for ((i, t) in tokens.withIndex()) {
            ids[i] = t.id.toLong()
            pauses[i] = t.pauseBucket.toLong()
            t.pooledHidden(hidden, i * hiddenDim)
            floats[i] = PcecFeatures.FLOAT_INPUT
        }
        return PcecInputs(ids, pauses, hidden, floats)
    }
}

internal class PcecInputs(val ids: LongArray, val pauses: LongArray, val hidden: FloatArray, val floats: FloatArray)
