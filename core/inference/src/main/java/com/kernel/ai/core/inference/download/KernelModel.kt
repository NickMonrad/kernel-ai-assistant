package com.kernel.ai.core.inference.download

import android.content.Context
import com.kernel.ai.core.inference.hardware.HardwareTier
import java.io.File

/**
 * Catalogue of all on-device models that Kernel AI can download and use.
 *
 * URLs point to the HuggingFace `resolve/main/` endpoint which follows LFS redirects
 * automatically when fetched with [java.net.HttpURLConnection].
 */
enum class KernelModel(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    /** Approximate file size in bytes — used for storage space checks before download. */
    val approxSizeBytes: Long,
    /** If true, the app requires this model to function at all. */
    val isRequired: Boolean,
    /**
     * If non-null, this model is the preferred conversation model for that tier.
     * Null means the model is suitable for any tier (or is not a conversation model).
     */
    val preferredForTier: HardwareTier?,
    /**
     * If `true`, this model is gated on HuggingFace and requires an authenticated
     * access token to download. The [ModelDownloadManager] will attach a Bearer token
     * to the download request when this is `true`.
     */
    val isGated: Boolean = false,
    /**
     * URL to the HuggingFace model licence page. Non-null for gated models — shown in the
     * Model Management UI so users can accept the licence before downloading.
     */
    val licenceUrl: String? = null,
    /**
     * If `true`, this model is bundled as an app asset and is always available without
     * downloading. Model Management shows it as "Built-in" — no download or delete controls.
     */
    val isBundled: Boolean = false,
    /**
     * If `false`, this model is managed by a feature-specific UI (e.g. Voice settings) and
     * should be hidden from the generic Model Management screen to prevent partial installs.
     * Defaults to `true` so existing entries are unaffected.
     */
    val showInModelManagement: Boolean = true,
) {
    GEMMA_4_E2B(
        displayName = "Gemma 4 E-2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_583_085_056L,
        isRequired = true,
        preferredForTier = null,
        isGated = false,
        licenceUrl = null,
    ),

    GEMMA_4_E4B(
        displayName = "Gemma 4 E-4B",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        approxSizeBytes = 3_654_467_584L,
        isRequired = false,
        preferredForTier = HardwareTier.FLAGSHIP,
        isGated = false,
        licenceUrl = null,
    ),

    EMBEDDING_GEMMA_300M(
        displayName = "EmbeddingGemma 300M",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.tflite",
        approxSizeBytes = 171_000_000L,
        isRequired = true,
        preferredForTier = null,
        isGated = true,
        licenceUrl = "https://huggingface.co/litert-community/embeddinggemma-300m",
    ),

    EMBEDDING_GEMMA_300M_SM8550(
        displayName = "EmbeddingGemma 300M (SM8550)",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        approxSizeBytes = 350_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = true,
        licenceUrl = "https://huggingface.co/litert-community/embeddinggemma-300m",
    ),

    EMBEDDING_GEMMA_SP_MODEL(
        displayName = "EmbeddingGemma SentencePiece model",
        fileName = "sentencepiece.model",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/sentencepiece.model",
        approxSizeBytes = 4_500_000L,
        isRequired = true,
        preferredForTier = null,
        isGated = true,
        licenceUrl = "https://huggingface.co/litert-community/embeddinggemma-300m",
    ),

    MINI_LM(
        displayName = "MiniLM-L6 Intent Classifier",
        fileName = "minilm-l6-v2-int8.tflite",
        downloadUrl = "",
        approxSizeBytes = 23_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        isBundled = true,
    ),

    SHERPA_STT_ENCODER(
        displayName = "Sherpa STT Encoder",
        fileName = "sherpa-stt-encoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
        approxSizeBytes = 67_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_STT_DECODER(
        displayName = "Sherpa STT Decoder",
        fileName = "sherpa-stt-decoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main/decoder-epoch-99-avg-1.int8.onnx",
        approxSizeBytes = 3_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_STT_JOINER(
        displayName = "Sherpa STT Joiner",
        fileName = "sherpa-stt-joiner.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main/joiner-epoch-99-avg-1.int8.onnx",
        approxSizeBytes = 2_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_STT_TOKENS(
        displayName = "Sherpa STT Tokens",
        fileName = "sherpa-stt-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main/tokens.txt",
        approxSizeBytes = 75_000L, // ~75 KB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    // ── Sherpa-ONNX SenseVoice int8 (Offline) ────────────────────────────────
    //
    // From csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2022-11-09
    // (HuggingFace, Apache 2.0, gated).  Single model int8 + tokens.
    // Downloaded on demand when the user selects "Sherpa-ONNX SenseVoice"
    // in Settings → Voice. Requires Hugging Face licence acceptance.

    SHERPA_SENSEVOICE_MODEL(
        displayName = "Sherpa SenseVoice Model",
        fileName = "sherpa-sensevoice-model.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2022-11-09/resolve/main/model.int8.onnx",
        approxSizeBytes = 100_000_000L, // ~100 MB
        isRequired = false,
        preferredForTier = null,
        isGated = true,
        licenceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2022-11-09",
        showInModelManagement = false,
    ),

    SHERPA_SENSEVOICE_TOKENS(
        displayName = "Sherpa SenseVoice Tokens",
        fileName = "sherpa-sensevoice-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2022-11-09/resolve/main/tokens.txt",
        approxSizeBytes = 100_000L, // ~100 KB
        isRequired = false,
        preferredForTier = null,
        isGated = true,
        licenceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2022-11-09",
        showInModelManagement = false,
    ),

    // ── Sherpa-ONNX Whisper tiny.en int8 (Offline) ───────────────────────────
    //
    // From csukuangfj/sherpa-onnx-whisper-tiny.en
    // (HuggingFace, Apache 2.0, public / ungated).  Encoder + decoder + tokens.
    // Downloaded on demand when the user selects "Sherpa-ONNX Whisper tiny.en"
    // in Settings → Voice.

    SHERPA_WHISPER_TINY_EN_ENCODER(
        displayName = "Sherpa Whisper tiny.en Encoder",
        fileName = "sherpa-whisper-tiny.en-encoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx",
        approxSizeBytes = 74_000_000L, // ~74 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_WHISPER_TINY_EN_DECODER(
        displayName = "Sherpa Whisper tiny.en Decoder",
        fileName = "sherpa-whisper-tiny.en-decoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx",
        approxSizeBytes = 43_000_000L, // ~43 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_WHISPER_TINY_EN_TOKENS(
        displayName = "Sherpa Whisper tiny.en Tokens",
        fileName = "sherpa-whisper-tiny.en-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
        approxSizeBytes = 150_000L, // ~150 KB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    // ── Sherpa-ONNX Paraformer int8 (Streaming) ──────────────────────────────
    //
    // From csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
    // (HuggingFace, Apache 2.0, public / ungated).  Encoder + decoder + tokens.
    // Downloaded on demand when the user selects "Sherpa-ONNX Paraformer"
    // in Settings → Voice.

    SHERPA_PARAFORMER_ENCODER(
        displayName = "Sherpa Paraformer Encoder",
        fileName = "sherpa-paraformer-encoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx",
        approxSizeBytes = 120_000_000L, // ~120 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_PARAFORMER_DECODER(
        displayName = "Sherpa Paraformer Decoder",
        fileName = "sherpa-paraformer-decoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx",
        approxSizeBytes = 100_000_000L, // ~100 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_PARAFORMER_TOKENS(
        displayName = "Sherpa Paraformer Tokens",
        fileName = "sherpa-paraformer-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt",
        approxSizeBytes = 100_000L, // ~100 KB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    );
    /**
     * Stable, single-sourced identifier for this model, used as the Room primary key in
     * `com.kernel.ai.core.memory.entity.ModelSettingsEntity`.
     *
     * Derived from the enum entry name in lowercase, e.g.:
     * - [GEMMA_4_E2B] → `"gemma_4_e2b"`
     * - [GEMMA_4_E4B] → `"gemma_4_e4b"`
     *
     * Never derive keys from [name] at call sites — always use this property.
     */
    val modelId: String get() = name.lowercase()
}

/** Absolute path to this model's file on external app storage (survives reinstall). */
fun KernelModel.localFile(context: Context): File {
    val modelsDir = context.getExternalFilesDir("models")
        ?: File(context.filesDir, "models") // fallback if external storage unavailable
    modelsDir.mkdirs()
    return File(modelsDir, fileName)
}

/** True if the model file exists and is non-empty. Bundled models are always considered downloaded. */
fun KernelModel.isDownloaded(context: Context): Boolean {
    if (isBundled) return true
    val file = localFile(context)
    return file.exists() && file.length() > 0
}
