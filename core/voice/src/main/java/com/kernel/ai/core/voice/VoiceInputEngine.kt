package com.kernel.ai.core.voice

enum class VoiceInputEngine(
    val displayName: String,
    val description: String,
    val warning: String? = null,
    val isSherpaFamily: Boolean = false,
    val debugOnly: Boolean = false,
) {
    Auto(
        displayName = "Automatic (recommended)",
        description = "Uses the offline asr4all model once it is downloaded. If you allow online speech recognition, uses Android speech recognition instead.",
        debugOnly = true,
    ),
    AndroidNative(
        displayName = "Android native",
        description = "Uses Android's speech recognition: on-device, or your phone's speech service when online speech recognition is on. May work better for some accents or devices.",
        warning = "Android native speech recognition depends on device support and installed language packs. With online speech recognition on, Jandal uses your phone's speech service straight away when on-device recognition isn't confirmed for your language. With it off, voice input stays on the phone.",
    ),
    SherpaSenseVoice(
        displayName = "Sherpa-ONNX SenseVoice",
        description = "Local offline batch speech recognition via Sherpa-ONNX SenseVoice int8. ~100 MB. Final result only — no streaming partials.",
        warning = "SenseVoice processes the complete utterance after recording stops. No partial/streaming results.",
        isSherpaFamily = true,
        debugOnly = true,
    ),
    SherpaWhisper(
        displayName = "Sherpa-ONNX Whisper tiny.en",
        description = "Local offline batch speech recognition via Sherpa-ONNX Whisper tiny.en int8. ~117 MB. Final result only — no streaming partials.",
        warning = "Whisper processes the complete utterance after recording stops. No partial/streaming results.",
        isSherpaFamily = true,
        debugOnly = true,
    ),
    SherpaParaformer(
        displayName = "Sherpa-ONNX Paraformer",
        description = "Local streaming speech recognition via Sherpa-ONNX Paraformer int8. ~226 MB. Streaming partial results.",
        isSherpaFamily = true,
        debugOnly = true,
    ),
    Asr4all(
        displayName = "asr4all streaming",
        description = "Offline streaming speech recognition with the asr4all model (ExecuTorch). M is about 60 MB; L, for capable phones, is about 101 MB. Streaming partial results.",
        warning = "Experimental. Accuracy has not yet been compared with other offline models; compare on your own speech before relying on it.",
    ),
    ;

    /** Engines whose model files are downloaded from Settings → Voice (Sherpa family and asr4all). */
    val hasDownloadableModel: Boolean get() = isSherpaFamily || this == Asr4all

    companion object {
        /**
         * Maps a stored engine name to an engine. "SherpaOnnx" and "SherpaZipformer" were the Zipformer engine, removed
         * in #1982, so they land on [Auto]. "Vosk" was removed (#1008), so former Vosk users also land on [Auto].
         * Missing or unknown values also resolve to [Auto].
         */
        fun fromStorage(value: String?): VoiceInputEngine =
            if (value == "SherpaOnnx" || value == "SherpaZipformer") Auto
            else entries.firstOrNull { it.name == value } ?: Auto

        /** Release v0.1 excludes the unresolved Sherpa runtime; debug keeps the full catalogue. */
        fun entriesForBuild(isRelease: Boolean): List<VoiceInputEngine> =
            entries.filter { engine -> !isRelease || !engine.debugOnly }

        /** Stored debug/Sherpa choices resolve safely to Android native in release builds. */
        fun resolveForBuild(value: String?, isRelease: Boolean): VoiceInputEngine {
            val engine = fromStorage(value)
            return engine.takeIf { it in entriesForBuild(isRelease) } ?: AndroidNative
        }
    }
}
