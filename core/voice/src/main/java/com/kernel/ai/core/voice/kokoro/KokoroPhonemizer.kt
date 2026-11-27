package com.kernel.ai.core.voice.kokoro

import java.io.Closeable

/** Turns English text into the IPA phoneme string Kokoro's token table reads. */
internal interface KokoroPhonemizer : Closeable {
    /** Phonemizes all of [text], or throws without returning partial output. */
    fun phonemize(text: String): String
}
