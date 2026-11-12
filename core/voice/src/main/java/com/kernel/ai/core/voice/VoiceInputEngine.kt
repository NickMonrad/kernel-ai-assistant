package com.kernel.ai.core.voice

enum class VoiceInputEngine(
    val displayName: String,
    val description: String,
    val warning: String? = null,
    val isSherpaFamily: Boolean = false,
) {
    Auto(
        displayName = "Automatic (recommended)",
        description = "Uses the offline Sherpa-ONNX Zipformer model. If you allow Google's speech service below, uses Android speech recognition instead.",
    ),
    AndroidNative(
        displayName = "Android native",
        description = "Uses Android's on-device speech recognizer, which may work better for some accents or devices.",
        warning = "Android native speech recognition depends on device support and installed language packs. It only falls back to Google's online speech service if you allow that below.",
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
        /**
         * Maps a stored engine name to an engine. Legacy values migrate: "SherpaOnnx" was the
         * Zipformer engine before the Sherpa family split, and "Vosk" was removed (#1008), so
         * former Vosk users land on [Auto]. Missing or unknown values also resolve to [Auto].
         */
        fun fromStorage(value: String?): VoiceInputEngine =
            if (value == "SherpaOnnx") SherpaZipformer
            else entries.firstOrNull { it.name == value } ?: Auto
    }
}
