package com.kernel.ai.core.voice

/**
 * Returns true when [this] transcript contains a recognisable form of "Hey Jandal".
 *
 * Matches across common ASR error modes (Handel/Handal/Jandel/Jando) and normalises case.
 *
 * #1439: the wake-verifier model (Whisper tiny.en) transcribes the fixed natural
 * wake phrase as `hi, jandal` — the `hi` prefix and an optional inline comma/period
 * are evidence-backed supported forms. `jando` mirrors the already-accepted `hando`
 * truncation observed at candidate-window edge placements.
 */
fun String.containsWakePhrase(): Boolean {
    val lower = lowercase()
    val namePattern = Regex("""\b(?:hey|hi|a)[,.]?\s*(?:jandal|jandel|handel|handal|hando|jando)\b""")
    return namePattern.containsMatchIn(lower)
}
