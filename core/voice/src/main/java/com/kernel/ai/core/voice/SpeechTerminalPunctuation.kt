package com.kernel.ai.core.voice

/**
 * Sentence-end checks shared by the speech text preparers (#2177). A sentence mark followed by closing quotes or
 * brackets ("…not wings.\"", "…the ground.\u201D") already ends the sentence, so a preparer must not add a second
 * full stop after it. Straight and curly quotes count the same.
 */
object SpeechTerminalPunctuation {
    const val CLOSERS = "\"'\u201D\u2019)]}"

    /** True when [text], ignoring trailing closing quotes and brackets, ends with one of [marks]. */
    fun endsWith(text: String, marks: String): Boolean {
        val core = text.trimEnd { it in CLOSERS || it.isWhitespace() }
        return core.isNotEmpty() && core.last() in marks
    }
}
