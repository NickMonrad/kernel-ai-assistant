package com.kernel.ai.debug.tts.kokoro

import java.io.Closeable

data class PhonemizedWord(
    val text: String,
    /** UTF-16 offset in the original input. */
    val offset: Int,
    /** UTF-16 offset in [PhonemizedText.phonemes]. */
    val phonemeOffset: Int,
    /** UTF-16 length in [PhonemizedText.phonemes]. */
    val phonemeLength: Int,
)

data class PhonemizedText(
    val phonemes: String,
    val words: List<PhonemizedWord>,
)

interface KokoroPhonemizer : Closeable {
    /** Phonemizes all of [text], or throws without returning partial output. */
    fun phonemize(text: String): PhonemizedText
}
