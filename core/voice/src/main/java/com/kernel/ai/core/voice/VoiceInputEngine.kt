package com.kernel.ai.core.voice

enum class VoiceInputEngine(
    val displayName: String,
    val description: String,
    val warning: String? = null,
) {
    Vosk(
        displayName = "Vosk",
        description = "Offline-first voice recognition using the bundled local model.",
    ),
    AndroidNative(
        displayName = "Android native",
        description = "Uses the platform speech recognizer and may work better for some accents or devices.",
        warning = "Android native speech recognition may depend on device support, installed language packs, and recognizer availability. Offline behavior is not guaranteed on all devices.",
    ),
    SherpaOnnx(
        displayName = "Sherpa-ONNX (Local)",
        description = "Fully offline streaming speech recognition using the Sherpa-ONNX Zipformer model. Highest accuracy for NZ English with no network dependency.",
        warning = null,
    ),
    WhisperCpp(
        displayName = "Whisper.cpp",
        description = "Offline speech recognition using whisper.cpp (tiny model). Strong general-purpose transcription quality.",
        warning = "Requires downloading the whisper.cpp model file (~75 MB). Push-to-talk only — no streaming partials.",
    ),
    ParakeetCtc(
        displayName = "Parakeet CTC",
        description = "Offline speech recognition using NVIDIA's Parakeet CTC model via TFLite. High accuracy for English.",
        warning = "Requires downloading the Parakeet model (~596 MB INT8) and tokenizer (~4 MB). Push-to-talk only — no streaming partials.",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): VoiceInputEngine =
            entries.firstOrNull { it.name == value } ?: Vosk
    }
}
