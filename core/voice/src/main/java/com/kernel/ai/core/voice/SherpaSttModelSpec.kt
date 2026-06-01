package com.kernel.ai.core.voice

/**
 * Describes a Sherpa-ONNX STT model family: required model file names, reflected class names,
 * and whether the audio loop is online (streaming) or offline (batch).
 */
data class SherpaSttModelSpec(
    val engine: VoiceInputEngine,
    val displayLabel: String,
    val subtitle: String,
    val recognizerKind: RecognizerKind,
    /** Reflected class basenames under [PKG] (e.g. "OnlineRecognizer"). */
    val recognizerClassName: String,
    val streamClassName: String,
    val modelConfigClassName: String,
    val recognizerConfigClassName: String,
    /** File names (not paths) for the required ONNX / tokens files. */
    val requiredFileNames: List<String>,
    /** Approximate total download size for display. */
    val approxTotalBytes: Long,
) {
    enum class RecognizerKind {
        /** Zipformer / Paraformer — feeds PCM chunks, decodes incrementally. */
        Online,
        /** Whisper / SenseVoice — feeds full PCM, decodes once. */
        Offline,
    }

    companion object {
        private const val PKG = "com.k2fsa.sherpa.onnx"

        val ZIPFORMER = SherpaSttModelSpec(
            engine = VoiceInputEngine.SherpaZipformer,
            displayLabel = "Zipformer int8",
            subtitle = "Zipformer int8 · English · Fully offline",
            recognizerKind = RecognizerKind.Online,
            recognizerClassName = "$PKG.OnlineRecognizer",
            streamClassName = "$PKG.OnlineStream",
            modelConfigClassName = "$PKG.OnlineModelConfig",
            recognizerConfigClassName = "$PKG.OnlineRecognizerConfig",
            requiredFileNames = listOf(
                "sherpa-stt-encoder.int8.onnx",
                "sherpa-stt-decoder.int8.onnx",
                "sherpa-stt-joiner.int8.onnx",
                "sherpa-stt-tokens.txt",
            ),
            approxTotalBytes = 72_075_000L,
        )

        val SENSE_VOICE = SherpaSttModelSpec(
            engine = VoiceInputEngine.SherpaSenseVoice,
            displayLabel = "SenseVoice int8",
            subtitle = "SenseVoice int8 · English · Offline (final only)",
            recognizerKind = RecognizerKind.Offline,
            recognizerClassName = "$PKG.OfflineRecognizer",
            streamClassName = "$PKG.OfflineStream",
            modelConfigClassName = "$PKG.OfflineModelConfig",
            recognizerConfigClassName = "$PKG.OfflineRecognizerConfig",
            requiredFileNames = listOf(
                "sherpa-sensevoice-model.int8.onnx",
                "sherpa-sensevoice-tokens.txt",
            ),
            approxTotalBytes = 100_100_000L,
        )

        val WHISPER = SherpaSttModelSpec(
            engine = VoiceInputEngine.SherpaWhisper,
            displayLabel = "Whisper tiny.en int8",
            subtitle = "Whisper tiny.en int8 · English · Offline (final only)",
            recognizerKind = RecognizerKind.Offline,
            recognizerClassName = "$PKG.OfflineRecognizer",
            streamClassName = "$PKG.OfflineStream",
            modelConfigClassName = "$PKG.OfflineModelConfig",
            recognizerConfigClassName = "$PKG.OfflineRecognizerConfig",
            requiredFileNames = listOf(
                "sherpa-whisper-tiny.en-encoder.int8.onnx",
                "sherpa-whisper-tiny.en-decoder.int8.onnx",
                "sherpa-whisper-tiny.en-tokens.txt",
            ),
            approxTotalBytes = 117_150_000L,
        )

        val PARAFORMER = SherpaSttModelSpec(
            engine = VoiceInputEngine.SherpaParaformer,
            displayLabel = "Paraformer int8",
            subtitle = "Paraformer int8 · English · Streaming",
            recognizerKind = RecognizerKind.Online,
            recognizerClassName = "$PKG.OnlineRecognizer",
            streamClassName = "$PKG.OnlineStream",
            modelConfigClassName = "$PKG.OnlineModelConfig",
            recognizerConfigClassName = "$PKG.OnlineRecognizerConfig",
            requiredFileNames = listOf(
                "sherpa-paraformer-encoder.int8.onnx",
                "sherpa-paraformer-decoder.int8.onnx",
                "sherpa-paraformer-tokens.txt",
            ),
            approxTotalBytes = 220_100_000L,
        )

        /** All Sherpa STT specs, keyed by engine for fast lookup. */
        val ALL: Map<VoiceInputEngine, SherpaSttModelSpec> = listOf(
            ZIPFORMER, SENSE_VOICE, WHISPER, PARAFORMER,
        ).associateBy { it.engine }

        fun forEngine(engine: VoiceInputEngine): SherpaSttModelSpec? = ALL[engine]

        /**
         * Returns the default spec used for wake-word verification.
         * Uses Zipformer since it is the original/supported streaming model.
         */
        val WAKE_VERIFICATION_DEFAULT: SherpaSttModelSpec get() = ZIPFORMER
    }
}