package com.kernel.ai.core.voice.asr4all

/**
 * Turns PCEC output slots into text.
 *
 * Slots form one CTC-style sequence across all calls: identical adjacent slots collapse and
 * blanks are dropped. The surviving ids are lower-case word pieces (a leading space starts a
 * word) and control ids: `cap` capitalises the next piece's first letter, `allcaps`
 * upper-cases the next word, and `period`/`comma`/`question` append their mark to the text so
 * far. Sentence-initial capitals (first word, and after `.` or `?`) are applied last, as the
 * model card describes.
 */
class PcecTextRenderer(private val pcec: Asr4AllPcecConfig) {

    /** Collapses repeats and drops blanks across the concatenation of [parts]. */
    fun collapse(vararg parts: IntSlice): IntArray {
        val out = IntArray(parts.sumOf { it.length })
        var n = 0
        var previous = -1
        for (part in parts) {
            for (i in part.from until part.from + part.length) {
                val slot = part.data[i]
                if (slot != previous && slot != pcec.blankId) out[n++] = slot
                previous = slot
            }
        }
        return out.copyOf(n)
    }

    fun render(ids: IntArray): String {
        val text = StringBuilder()
        var capNext = false
        var allCapsWord = false
        var allCapsPending = false
        for (id in ids) {
            when (id) {
                pcec.capId -> capNext = true
                pcec.allCapsId -> allCapsPending = true
                pcec.periodId -> appendMark(text, '.')
                pcec.commaId -> appendMark(text, ',')
                pcec.questionId -> appendMark(text, '?')
                else -> {
                    if (id < 0 || id >= pcec.vocab.size) continue
                    val piece = pcec.vocab[id]
                    val startsWord = piece.startsWith(" ") || text.isEmpty()
                    if (startsWord) allCapsWord = false
                    if (allCapsPending) {
                        allCapsWord = true
                        allCapsPending = false
                    }
                    var out = if (allCapsWord) piece.uppercase() else piece
                    if (capNext) {
                        out = capitaliseFirstLetter(out)
                        capNext = false
                    }
                    text.append(out)
                }
            }
        }
        return sentenceCase(text.toString().trim())
    }

    private fun appendMark(text: StringBuilder, mark: Char) {
        // A mark before any word has nothing to attach to.
        if (text.isBlank()) return
        text.append(mark)
    }

    private fun capitaliseFirstLetter(piece: String): String {
        val i = piece.indexOfFirst { it.isLetter() }
        if (i < 0) return piece
        return piece.substring(0, i) + piece[i].uppercaseChar() + piece.substring(i + 1)
    }

    private fun sentenceCase(text: String): String {
        val out = StringBuilder(text.length)
        var atSentenceStart = true
        for (c in text) {
            if (atSentenceStart && c.isLetter()) {
                out.append(c.uppercaseChar())
                atSentenceStart = false
            } else {
                out.append(c)
                if (c == '.' || c == '?') atSentenceStart = true else if (!c.isWhitespace()) atSentenceStart = false
            }
        }
        return out.toString()
    }
}

/** A view of `data[from until from + length]`. */
class IntSlice(val data: IntArray, val from: Int = 0, val length: Int = data.size - from)
