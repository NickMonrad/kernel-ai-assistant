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
) {
    GEMMA_4_E2B(
        displayName = "Gemma 4 E-2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_583_085_056L, // 2.4 GB
        isRequired = true,
        /** Suitable for all hardware tiers. */
        preferredForTier = null,
        // Ungated since Apr 2026 — no auth token required.
        isGated = false,
    ),

    /**
     * Higher-quality model auto-selected on FLAGSHIP devices (≥10 GB RAM).
     * Not required — the app falls back to [GEMMA_4_E2B] if not downloaded.
     */
    GEMMA_4_E4B(
        displayName = "Gemma 4 E-4B",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        approxSizeBytes = 3_654_467_584L, // 3.4 GB
        isRequired = false,
        preferredForTier = HardwareTier.FLAGSHIP,
        // Ungated since Apr 2026 — no auth token required.
        isGated = false,
    ),

    /**
     * Not currently used — FunctionGemmaRouter has been superseded by the native Gemma 4
     * control-token tool-calling path (#242). Kept in the catalogue (not required) so the
     * enum reference in FunctionGemmaRouter/ActionsViewModel compiles; will be wired back in
     * when the fast intent layer (#220) is implemented.
     */
    FUNCTION_GEMMA_270M(
        displayName = "FunctionGemma 270M (intent router)",
        fileName = "mobile_actions_q8_ekv1024.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/mobile_actions_q8_ekv1024.litertlm",
        approxSizeBytes = 303_000_000L, // ~289 MB
        // Not required until #220 (fast intent layer) is implemented.
        isRequired = false,
        preferredForTier = null,
        isGated = true,
    ),

    /**
     * Universal Sentence Encoder — 512-dim text embeddings for RAG memory.
     * Public model (~6 MB) served from Google's MediaPipe CDN.
     * Not required for basic chat, but enables semantic memory in Phase 2.
     */
    UNIVERSAL_SENTENCE_ENCODER(
        displayName = "Universal Sentence Encoder",
        fileName = "universal_sentence_encoder.tflite",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite",
        approxSizeBytes = 6_120_274L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
    ),

    /**
     * EmbeddingGemma-300M — high-quality 1024-dim embeddings for RAG memory.
     * Generic build (CPU/GPU, all devices). Gated on HuggingFace — downloaded automatically
     * during onboarding once the user has authenticated.
     * seq512 variant: balanced context window vs. inference speed.
     */
    EMBEDDING_GEMMA_300M(
        displayName = "EmbeddingGemma 300M",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.tflite",
        approxSizeBytes = 350_000_000L,
        // Required — powers the RAG memory pipeline on all non-flagship devices.
        isRequired = true,
        preferredForTier = null,
        isGated = true,
    ),

    /**
     * SM8550 optimised variant — currently disabled due to LiteRT "Unsupported file format" error
     * on device. Keep isRequired=false and preferredForTier=null until upstream fix is available.
     */
    EMBEDDING_GEMMA_300M_SM8550(
        displayName = "EmbeddingGemma 300M (SM8550)",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        approxSizeBytes = 350_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = true,
    ),

    /**
     * SentencePiece tokeniser vocabulary required by all EmbeddingGemma variants.
     * Downloaded automatically during onboarding alongside the embedding model.
     */
    EMBEDDING_GEMMA_SP_MODEL(
        displayName = "EmbeddingGemma SentencePiece model",
        fileName = "sentencepiece.model",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/sentencepiece.model",
        approxSizeBytes = 4_500_000L,
        // Required — every EmbeddingGemma variant needs this tokeniser to run.
        isRequired = true,
        preferredForTier = null,
        isGated = true,
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

/** True if the model file exists and is non-empty. */
fun KernelModel.isDownloaded(context: Context): Boolean {
    val file = localFile(context)
    return file.exists() && file.length() > 0
}
