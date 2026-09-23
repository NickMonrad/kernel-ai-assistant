package com.kernel.ai.core.inference

/** BERT BasicTokenizer + greedy WordPiece encoding shared by embedding and intent models. */
object WordPieceTokenizer {
    private const val UNK = "[UNK]"
    private const val CLS = "[CLS]"
    private const val SEP = "[SEP]"

    /** Returns fixed-width token IDs and attention mask, preserving `[SEP]` after truncation. */
    fun encode(
        text: String,
        vocab: Map<String, Int>,
        maxSequenceLength: Int,
        lowerCase: Boolean = false,
        stripAccents: Boolean = false,
        maxWordLength: Int = 200,
    ): Pair<IntArray, IntArray> {
        require(maxSequenceLength >= 2) { "BERT sequence length must fit CLS and SEP" }
        require(maxWordLength > 0) { "WordPiece maximum word length must be positive" }
        val pieces = mutableListOf<String>()
        for (token in basicTokenize(text, lowerCase, stripAccents)) {
            pieces.addAll(wordPiece(token, vocab, maxWordLength))
        }
        val contentLimit = maxSequenceLength - 2
        val inputIds = IntArray(maxSequenceLength)
        val attentionMask = IntArray(maxSequenceLength)
        inputIds[0] = vocab[CLS] ?: vocab[UNK] ?: 0
        val retainedCount = minOf(contentLimit, pieces.size)
        for (index in 0 until retainedCount) {
            inputIds[index + 1] = vocab[pieces[index]] ?: (vocab[UNK] ?: 0)
            attentionMask[index + 1] = 1
        }
        inputIds[retainedCount + 1] = vocab[SEP] ?: vocab[UNK] ?: 0
        attentionMask[0] = 1
        attentionMask[retainedCount + 1] = 1
        return inputIds to attentionMask
    }

    private fun basicTokenize(text: String, lowerCase: Boolean, stripAccents: Boolean): List<String> {
        val cleaned = buildString(text.length) {
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val type = Character.getType(codePoint)
                when {
                    codePoint == 0 || codePoint == 0xFFFD -> Unit
                    (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) &&
                        codePoint != 9 && codePoint != 10 && codePoint != 13 -> Unit
                    else -> appendCodePoint(codePoint)
                }
                offset += Character.charCount(codePoint)
            }
        }
        var normalized = if (lowerCase) cleaned.lowercase(java.util.Locale.ROOT) else cleaned
        if (stripAccents) {
            val decomposed = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFD)
            normalized = buildString(decomposed.length) {
                var offset = 0
                while (offset < decomposed.length) {
                    val codePoint = decomposed.codePointAt(offset)
                    if (Character.getType(codePoint) != Character.NON_SPACING_MARK.toInt()) {
                        appendCodePoint(codePoint)
                    }
                    offset += Character.charCount(codePoint)
                }
            }
        }

        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += buffer.toString()
                buffer.clear()
            }
        }
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            when {
                isWhitespace(codePoint) -> flush()
                isChinese(codePoint) -> {
                    flush()
                    tokens += String(Character.toChars(codePoint))
                }
                isPunctuation(codePoint) -> {
                    flush()
                    tokens += String(Character.toChars(codePoint))
                }
                else -> buffer.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        flush()
        return tokens
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        codePoint == 0x20 || codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D ||
            Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

    private fun wordPiece(word: String, vocab: Map<String, Int>, maxWordLength: Int): List<String> {
        val codePointCount = word.codePointCount(0, word.length)
        if (codePointCount > maxWordLength) return listOf(UNK)
        val charOffsets = IntArray(codePointCount + 1)
        var charOffset = 0
        for (index in 0 until codePointCount) {
            charOffsets[index] = charOffset
            charOffset += Character.charCount(word.codePointAt(charOffset))
        }
        charOffsets[codePointCount] = word.length

        val pieces = mutableListOf<String>()
        var start = 0
        while (start < codePointCount) {
            var end = codePointCount
            var found: String? = null
            var foundEnd = start
            while (start < end) {
                val substring = word.substring(charOffsets[start], charOffsets[end])
                val candidate = if (start == 0) substring else "##$substring"
                if (candidate in vocab) {
                    found = candidate
                    foundEnd = end
                    break
                }
                end--
            }
            if (found == null) return listOf(UNK)
            pieces += found
            start = foundEnd
        }
        return pieces
    }

    private fun isPunctuation(codePoint: Int): Boolean {
        if (codePoint in 33..47 || codePoint in 58..64 || codePoint in 91..96 || codePoint in 123..126) return true
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChinese(codePoint: Int): Boolean = when (codePoint) {
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0x20000..0x2A6DF,
        in 0x2A700..0x2B73F, in 0x2B740..0x2B81F, in 0x2B820..0x2CEAF,
        in 0xF900..0xFAFF, in 0x2F800..0x2FA1F -> true
        else -> false
    }

}
