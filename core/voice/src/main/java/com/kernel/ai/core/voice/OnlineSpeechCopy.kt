package com.kernel.ai.core.voice

/** Copy for the "Use online speech recognition" switch, shared by setup and Settings > Voice (#1936, #1937). */
object OnlineSpeechCopy {
    const val TITLE = "Use online speech recognition"
    const val LINE = "Sends your voice to your phone's speech service (usually Google)."
    const val EXPLANATION =
        "Off: voice input never leaves this phone. On: Jandal uses Android speech recognition, which is " +
            "usually more accurate. Voice commands try on-device recognition first and retry with your phone's " +
            "speech service (usually Google) if that fails; Hey Jandal and alarm commands use that service " +
            "straight away. That service may send your audio off the phone."
}

/**
 * The online speech switch is shown under an engine row only when that row is the selected engine and the
 * engine is Automatic or Android native (the only engines it affects).
 */
fun onlineSpeechToggleVisible(selected: VoiceInputEngine, row: VoiceInputEngine): Boolean =
    row == selected && (row == VoiceInputEngine.Auto || row == VoiceInputEngine.AndroidNative)
