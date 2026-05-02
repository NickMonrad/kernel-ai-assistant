package com.kernel.ai.core.voice

enum class VoiceInputEngine(
    val displayName: String,
    val description: String,
    val warning: String? = null,
) {
    Vosk(
        displayName = "Vosk (Recommended)",
        description = "Offline-first voice recognition using the bundled local model.",
    ),
    AndroidNative(
        displayName = "Android native",
        description = "Uses the platform speech recognizer and may work better for some accents or devices.",
        warning = "Android native speech recognition may depend on device support, installed language packs, and recognizer availability. Offline behavior is not guaranteed on all devices.",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): VoiceInputEngine =
            entries.firstOrNull { it.name == value } ?: Vosk
    }
}
