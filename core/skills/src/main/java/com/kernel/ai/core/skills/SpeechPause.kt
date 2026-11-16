package com.kernel.ai.core.skills

/**
 * A pause inside spoken text (#1942), for example after the intro sentence of a calendar readout.
 *
 * Skills put [MARKER] in a `spokenSummary`. The chunked speech path splits there and asks the voice for
 * [DURATION_MS] of silence; any other consumer must call [strip] before speaking or showing the text.
 */
object SpeechPause {
    /** Paragraph separator: never typed in replies, and not whitespace for the speech normalisers' `\s`. */
    const val MARKER = "\u2029"

    /** Silence after the marked sentence. */
    const val DURATION_MS = 400L

    /** The text with every marker replaced by a space, for consumers that cannot pause. */
    fun strip(text: String): String = text.replace(MARKER, " ")
}
