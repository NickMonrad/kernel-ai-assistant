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
) {
    GEMMA_4_E2B(
        displayName = "Gemma 4 E-2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_583_085_056L, // 2.4 GB
        isRequired = true,
        /** Suitable for all hardware tiers. */
        preferredForTier = null,
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
    ),

    FUNCTION_GEMMA_270M(
        displayName = "FunctionGemma 270M (intent router)",
        fileName = "mobile_actions_q8_ekv1024.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/mobile_actions_q8_ekv1024.litertlm",
        approxSizeBytes = 303_000_000L, // ~289 MB
        // Not required for Phase 1 (basic chat). Needed for Phase 2 intent routing.
        // Note: this HuggingFace repo is currently gated — public URL needed before enabling.
        isRequired = false,
        preferredForTier = null,
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
    ),

    /**
     * EmbeddingGemma-300M — high-quality 1024-dim embeddings for RAG memory.
     * Generic build (CPU/GPU, all devices). Gated on HuggingFace — push manually via ADB.
     * seq512 variant: balanced context window vs. inference speed.
     */
    EMBEDDING_GEMMA_300M(
        displayName = "EmbeddingGemma 300M",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.tflite",
        approxSizeBytes = 350_000_000L,
        isRequired = false,
        preferredForTier = null,
    ),

    /**
     * EmbeddingGemma-300M — Qualcomm SM8550 (Snapdragon 8 Gen 2 / S23 Ultra) optimised build.
     * Uses Hexagon NPU for faster embedding inference. Push manually via ADB.
     */
    EMBEDDING_GEMMA_300M_SM8550(
        displayName = "EmbeddingGemma 300M (SM8550)",
        fileName = "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
        approxSizeBytes = 350_000_000L,
        isRequired = false,
        preferredForTier = null,
    ),

    /**
     * SentencePiece tokeniser vocabulary required by all EmbeddingGemma variants.
     * Push manually alongside the embedding model via ADB.
     */
    EMBEDDING_GEMMA_SP_MODEL(
        displayName = "EmbeddingGemma SentencePiece model",
        fileName = "sentencepiece.model",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/sentencepiece.model",
        approxSizeBytes = 4_500_000L,
        isRequired = false,
        preferredForTier = null,
    ),
}

/** Absolute path to this model's file on internal storage. */
fun KernelModel.localFile(context: Context): File =
    File(context.filesDir, "models/$fileName")

/** True if the model file exists and is non-empty. */
fun KernelModel.isDownloaded(context: Context): Boolean {
    val file = localFile(context)
    return file.exists() && file.length() > 0
}
