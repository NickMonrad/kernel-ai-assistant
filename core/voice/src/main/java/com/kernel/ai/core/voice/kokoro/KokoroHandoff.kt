package com.kernel.ai.core.voice.kokoro

import kotlinx.coroutines.CancellationException

/** One synthesis unit: a phoneme chunk of one sentence, plus the unspoken text from that sentence to the end of its append. */
internal class KokoroUnit(val chunk: String, val restOfAppend: String)

/**
 * Unspoken text of one Kokoro reply, handed to Inflect Micro after the first failed chunk (#2117). Set once: later
 * appends join the same remainder, so Kokoro never speaks again after the hand-off and no text is spoken twice.
 */
internal class KokoroHandoff {
    private val remainder = StringBuilder()

    /** Why Kokoro stopped speaking the reply; null while Kokoro still speaks it. */
    @Volatile var reason: String? = null
        private set

    val active: Boolean get() = reason != null

    /** Records the first failure. [restOfAppend] is the unspoken text from the failing unit to the end of its append. */
    @Synchronized
    fun failed(reason: String, restOfAppend: String) {
        if (active) return
        this.reason = reason
        append(restOfAppend)
    }

    /** Text appended after the hand-off; joins the remainder in order. */
    @Synchronized
    fun addText(text: String) {
        if (active) append(text)
    }

    @Synchronized
    fun remainder(): String = remainder.toString()

    private fun append(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (remainder.isNotEmpty()) remainder.append(' ')
        remainder.append(trimmed)
    }
}

/**
 * Runs [synthesize] for one unit. A failure hands the unit's rest-of-append to this hand-off and returns null, which
 * ends the append; the caller logs the hand-off. Cancellation propagates.
 */
internal suspend fun KokoroHandoff.synthesizeOrHandOff(
    unit: KokoroUnit,
    synthesize: suspend (String) -> FloatArray?,
): FloatArray? {
    if (active) return null
    return try {
        synthesize(unit.chunk)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        failed("Kokoro synthesis failed: ${error.message}", unit.restOfAppend)
        null
    }
}

/** Splits [text] after sentence-ending punctuation; each sentence is one phonemizer call. */
internal fun kokoroSentences(text: String): List<String> =
    text.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }

private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")

/**
 * Splits one append into units, one sentence at a time, so a failing sentence leaves exactly its unspoken text
 * behind. Once [handoff] is active the append goes to its remainder and yields nothing. [phonemeChunks] returns the
 * phoneme chunks for one sentence and may throw; a throw hands that sentence and the rest of the append over.
 */
internal fun kokoroSentenceUnits(
    text: String,
    handoff: KokoroHandoff,
    phonemeChunks: (sentence: String) -> List<String>,
): Sequence<KokoroUnit> {
    if (handoff.active) {
        handoff.addText(text)
        return emptySequence()
    }
    val sentences = kokoroSentences(text)
    return sequence {
        for ((index, sentence) in sentences.withIndex()) {
            val restOfAppend = sentences.subList(index, sentences.size).joinToString(" ")
            val chunks = try {
                phonemeChunks(sentence)
            } catch (error: Exception) {
                handoff.failed("Kokoro phonemizing failed: ${error.message}", restOfAppend)
                return@sequence
            }
            for (chunk in chunks) yield(KokoroUnit(chunk, restOfAppend))
        }
    }
}
