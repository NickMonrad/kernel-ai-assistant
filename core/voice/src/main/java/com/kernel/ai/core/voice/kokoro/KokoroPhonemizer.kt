package com.kernel.ai.core.voice.kokoro

import java.io.Closeable

/** Turns English text into the IPA phoneme string Kokoro's token table reads. */
internal interface KokoroPhonemizer : Closeable {
    /**
     * phonemis's own output for [text]. A word outside its lexicon gets no phonemes here; [KokoroOovFallback]
     * fills those in before Kokoro sees the string.
     */
    fun phonemize(text: String): PhonemizedText
}

/**
 * phonemis output built by the native phonemiser. [wordSpans] holds four ints per located input word, in code
 * points: input offset, input length, phoneme offset, phoneme length. Constructed from JNI; keep its constructor.
 */
internal class PhonemizedText(val phonemes: String, val wordSpans: IntArray)
