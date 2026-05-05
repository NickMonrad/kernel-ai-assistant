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
    ;

    companion object {
        fun fromStorage(value: String?): VoiceOutputEngine =
            entries.firstOrNull { it.name == value } ?: AndroidTts
    }
}
