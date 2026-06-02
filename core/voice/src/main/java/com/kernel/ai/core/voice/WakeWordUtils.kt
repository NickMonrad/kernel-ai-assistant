package com.kernel.ai.core.voice

/**
 * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
 *
 * Matches across common ASR error modes (Handel/Handal/Jandel) and normalises case.
 */
fun String.containsWakePhrase(): Boolean {
    val lower = lowercase()
    val namePattern = Regex("""\b(?:hey|a)\s*(?:jandal|jandel|handel|handal|hando)\b""")
    return namePattern.containsMatchIn(lower)
}
