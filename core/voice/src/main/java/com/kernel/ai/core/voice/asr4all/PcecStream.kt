package com.kernel.ai.core.voice.asr4all

import kotlin.math.floor
import kotlin.math.ln

/** One acoustic token as the PCEC head consumes it. */
class PcecToken(
    val id: Int,
    val startFrame: Int,
    var endFrame: Int,
    /** Bucketed pause before the token, or the blackout bucket when unknown. */
    val pauseBucket: Int,
    /** Sum of the encoder hidden rows over the token's frames; see [pooledHidden]. */
    val hiddenSum: FloatArray,
    var hiddenFrames: Int = 0,
) {
    fun addHidden(hidden: FloatArray, offset: Int) {
        for (i in hiddenSum.indices) hiddenSum[i] += hidden[offset + i]
        hiddenFrames++
    }

    /** Mean of the encoder hidden rows over the frames of the token's run. */
    fun pooledHidden(into: FloatArray, offset: Int) {
        val scale = 1f / hiddenFrames.coerceAtLeast(1)
        for (i in hiddenSum.indices) into[offset + i] = hiddenSum[i] * scale
    }
}

/**
 * Input features for the PCEC head, derived from the CTC timing.
 *
 * The pause before a token is the number of non-token frames between the previous token's
 * last frame and this token's first frame, bucketed on a log2 scale into
 * [Asr4AllPcecConfig.pauseBuckets] buckets (0 frames → 0, 1 → 1, 2–3 → 2, 4–7 → 3, …, capped at
 * the last bucket). The first token of an utterance has no previous token, so its pause is
 * the blackout bucket. These definitions were chosen by black-box measurement (see
 * CLEANROOM.md) and are the main candidates for parity adjustment.
 */
internal object PcecFeatures {
    fun pauseBucket(pcec: Asr4AllPcecConfig, blankFramesBefore: Int?): Int {
        if (blankFramesBefore == null) return pcec.pauseBlackout
        if (blankFramesBefore <= 0) return 0
        val log2 = floor(ln(blankFramesBefore.toDouble()) / ln(2.0) + 1e-9).toInt()
        return (log2 + 1).coerceAtMost(pcec.pauseBuckets - 1)
    }

    /** Value of the head's per-token float input (fourth input). */
    @Suppress("UNUSED_PARAMETER")
    fun floatFeature(token: PcecToken): Float = 0f
}

/**
 * Drives the PCEC head over the token stream. Full rungs of tokens go through the streaming
 * method, which returns the slots of tokens [Asr4AllPcecConfig.lagTokens] behind; those slots are
 * settled. The flush method returns the slots of the held-back tokens plus up to one rung of
 * pending tokens, and is used for the provisional tail of partials and for the final text.
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
    val renderer = PcecTextRenderer(pcec)

    val pendingCount: Int get() = pending.size

    fun add(token: PcecToken) {
        pending.addLast(token)
        while (pending.size >= rung) commitRung()
    }

    /**
     * Text of everything settled plus the flushed tail (pending tokens and [extra], an open
     * token). The flush's last slot is the end-of-input position, where the head places the
     * closing mark; it is used only when [final], so live partials do not end in a stray mark.
     */
    fun text(extra: PcecToken? = null, final: Boolean): String {
        // add() commits every full rung, so pending plus one open token never exceeds a rung.
        val tail = ArrayList<PcecToken>(pending.size + 1).apply {
            addAll(pending)
            if (extra != null) add(extra)
        }
        check(tail.size <= rung) { "PCEC tail ${tail.size} exceeds rung $rung" }
        val n = tail.size
        val flush = program.pcecFlush(tier, encode(tail, 0, n), n, state, seen)
        val tokenSlots = pcec.slotsPerToken * (pcec.lagTokens + n)
        val used = if (final) tokenSlots + 1 else tokenSlots
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
        val out = program.pcecStream(tier, encode(tokens, 0, rung), state, seen)
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

    /** Packs `tokens[start until start + n]` into one rung of inputs, padding the rest. */
    private fun encode(tokens: List<PcecToken>, start: Int, n: Int): PcecInputs {
        val ids = LongArray(rung) { pcec.padInputId.toLong() }
        val pauses = LongArray(rung)
        val hidden = FloatArray(rung * hiddenDim)
        val floats = FloatArray(rung)
        for (i in 0 until n) {
            val t = tokens[start + i]
            ids[i] = t.id.toLong()
            pauses[i] = t.pauseBucket.toLong()
            t.pooledHidden(hidden, i * hiddenDim)
            floats[i] = PcecFeatures.floatFeature(t)
        }
        return PcecInputs(ids, pauses, hidden, floats)
    }
}

internal class PcecInputs(val ids: LongArray, val pauses: LongArray, val hidden: FloatArray, val floats: FloatArray)
