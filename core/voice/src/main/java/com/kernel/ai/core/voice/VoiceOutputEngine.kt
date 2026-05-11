package com.kernel.ai.core.voice

enum class VoiceOutputEngine(
    val displayName: String,
    val description: String,
) {
    AndroidTts(
        displayName = "Android TTS",
        description = "Uses the platform text-to-speech engine and works without local Sherpa assets.",
    ),
    SherpaExperimental(
        displayName = "Sherpa Piper (Experimental)",
        description = "Uses locally prepared Sherpa-ONNX Piper voices and falls back to Android TTS if setup is missing or playback fails.",
    ),
    KokoroExperimental(
        displayName = "Kokoro (Experimental)",
        description = "Studio-grade Kokoro-82M TTS. Requires a ~130MB download. Falls back to Android TTS if unavailable.",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): VoiceOutputEngine =
            entries.firstOrNull { it.name == value } ?: AndroidTts
    }
}
