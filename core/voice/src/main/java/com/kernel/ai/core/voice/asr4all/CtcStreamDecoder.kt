package com.kernel.ai.core.voice.asr4all

/**
 * Greedy CTC decoding across streaming chunks into [PcecToken]s.
 *
 * Each frame's argmax label either starts a token, continues the open token (the same label
 * repeated) or is blank. A token stays open while its label repeats, including into the next
 * chunk, and is emitted when its run ends, so the chunking never changes the token sequence.
 * Each token carries the PCEC features: its pause bucket (from the gap after the previous
 * token) and the encoder hidden rows over its run.
 */
class CtcStreamDecoder(private val config: Asr4AllModelConfig) {
    private val vocabSize = config.vocabSize
    private val blankId = config.blankId
    private val hiddenDim = config.hiddenDim
    private var previousLabel = -1
    private var nextFrame = 0
    private var lastTokenEnd = -1

    /** The token whose run may still continue into the next chunk, if any. */
    var openToken: PcecToken? = null
        private set

    /**
     * Decodes [frames] frames: row-major `[frames, vocabSize]` [logits] and `[frames, hiddenDim]`
     * [hidden]. Calls [onToken] for each token whose run ended; returns true if a token started.
     */
    fun accept(logits: FloatArray, hidden: FloatArray, frames: Int, onToken: (PcecToken) -> Unit): Boolean {
        var started = false
        for (f in 0 until frames) {
            val frame = nextFrame++
            val label = argmax(logits, f * vocabSize)
            when {
                label == blankId -> close(onToken)
                label == previousLabel -> openToken!!.let {
                    it.endFrame = frame
                    it.addHidden(hidden, f * hiddenDim)
                }
                else -> {
                    close(onToken)
                    val gap = if (lastTokenEnd < 0) null else frame - lastTokenEnd - 1
                    openToken = PcecToken(
                        id = label,
                        startFrame = frame,
                        endFrame = frame,
                        pauseBucket = PcecFeatures.pauseBucket(config.pcec, gap),
                        hiddenSum = FloatArray(hiddenDim),
                    ).also { it.addHidden(hidden, f * hiddenDim) }
                    started = true
                }
            }
            previousLabel = label
        }
        return started
    }

    /** Ends the open token's run (end of input). */
    fun close(onToken: (PcecToken) -> Unit) {
        val token = openToken ?: return
        openToken = null
        lastTokenEnd = token.endFrame
        onToken(token)
    }

    fun reset() {
        previousLabel = -1
        nextFrame = 0
        lastTokenEnd = -1
        openToken = null
    }

    /** Argmax over `logits[base until base + vocabSize]`; ties resolve to the lowest id. */
    private fun argmax(logits: FloatArray, base: Int): Int {
        var best = 0
        var bestValue = logits[base]
        for (v in 1 until vocabSize) {
            val x = logits[base + v]
            if (x > bestValue) {
                bestValue = x
                best = v
            }
        }
        return best
    }
}
