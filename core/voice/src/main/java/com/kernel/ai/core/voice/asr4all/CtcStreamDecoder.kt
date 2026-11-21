package com.kernel.ai.core.voice.asr4all

/**
 * Greedy CTC decoding across streaming chunks into [PcecToken]s.
 *
 * Each frame's argmax label either starts a token, continues the current token's run (the same
 * label repeated) or is blank. A token is handed to PCEC ([onToken]) only when the next token
 * starts, or at end of input ([finish]), because its PCEC hidden input covers every frame from
 * its start up to the next token's start: its own run plus the blank frames after it. The
 * chunking therefore never changes the tokens or their features.
 */
class CtcStreamDecoder(private val config: Asr4AllModelConfig) {
    private val vocabSize = config.vocabSize
    private val blankId = config.blankId
    private val hiddenDim = config.hiddenDim
    private var previousLabel = -1
    private var nextFrame = 0
    private var lastTokenEnd = -1

    /** The latest token: not yet handed to PCEC, since its hidden span is still open. */
    var currentToken: PcecToken? = null
        private set

    /** Frames decoded so far in this utterance. */
    val framesDecoded: Int get() = nextFrame

    /**
     * Decodes [frames] frames: row-major `[frames, vocabSize]` [logits] and `[frames, hiddenDim]`
     * [hidden]. Calls [onToken] for each token whose span closed; returns true if any did.
     */
    fun accept(logits: FloatArray, hidden: FloatArray, frames: Int, onToken: (PcecToken) -> Unit): Boolean {
        var handed = false
        for (f in 0 until frames) {
            val frame = nextFrame++
            val label = argmax(logits, f * vocabSize)
            if (label != blankId && label != previousLabel) {
                currentToken?.let {
                    onToken(it)
                    handed = true
                }
                // Blank frames before the token; for the first token, the leading frames.
                val gap = if (lastTokenEnd < 0) frame else frame - lastTokenEnd - 1
                currentToken = PcecToken(
                    id = label,
                    startFrame = frame,
                    endFrame = frame,
                    pauseBucket = PcecFeatures.pauseBucket(config.pcec, gap),
                    hiddenDim = hiddenDim,
                )
            } else if (label != blankId) {
                currentToken!!.endFrame = frame
            }
            currentToken?.addHidden(hidden, f * hiddenDim)
            if (label != blankId) lastTokenEnd = frame
            previousLabel = label
        }
        return handed
    }

    /** End of input: hands over the last token. */
    fun finish(onToken: (PcecToken) -> Unit) {
        val token = currentToken ?: return
        currentToken = null
        onToken(token)
    }

    fun reset() {
        previousLabel = -1
        nextFrame = 0
        lastTokenEnd = -1
        currentToken = null
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
