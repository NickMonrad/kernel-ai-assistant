package com.kernel.ai.core.voice

enum class VoiceInputEngine(
    val displayName: String,
    val description: String,
    val warning: String? = null,
    val isSherpaFamily: Boolean = false,
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
    SherpaZipformer(
        displayName = "Sherpa-ONNX Zipformer",
        description = "Fully offline streaming speech recognition using the Sherpa-ONNX Zipformer model. Highest accuracy for NZ English with no network dependency.",
        isSherpaFamily = true,
    ),
    SherpaSenseVoice(
        displayName = "Sherpa-ONNX SenseVoice",
        description = "Local offline batch speech recognition via Sherpa-ONNX SenseVoice int8. ~100 MB. Final result only — no streaming partials.",
        warning = "SenseVoice processes the complete utterance after recording stops. No partial/streaming results.",
        isSherpaFamily = true,
    ),
    SherpaWhisper(
        displayName = "Sherpa-ONNX Whisper tiny.en",
        description = "Local offline batch speech recognition via Sherpa-ONNX Whisper tiny.en int8. ~117 MB. Final result only — no streaming partials.",
        warning = "Whisper processes the complete utterance after recording stops. No partial/streaming results.",
        isSherpaFamily = true,
    ),
    SherpaParaformer(
        displayName = "Sherpa-ONNX Paraformer",
        description = "Local streaming speech recognition via Sherpa-ONNX Paraformer int8. ~226 MB. Streaming partial results.",
        isSherpaFamily = true,
    ),
    ;

    companion object {
        fun fromStorage(value: String?): VoiceInputEngine =
            if (value == "SherpaOnnx") SherpaZipformer
            else entries.firstOrNull { it.name == value } ?: Vosk
    }
}
