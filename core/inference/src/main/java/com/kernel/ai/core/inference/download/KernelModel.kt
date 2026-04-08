package com.kernel.ai.core.inference.download

import android.content.Context
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
) {
    GEMMA_4_E2B(
        displayName = "Gemma 4 E-2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_583_085_056L, // 2.4 GB
        isRequired = true,
    ),

    FUNCTION_GEMMA_270M(
        displayName = "FunctionGemma 270M (intent router)",
        fileName = "mobile_actions_q8_ekv1024.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/mobile_actions_q8_ekv1024.litertlm",
        approxSizeBytes = 303_000_000L, // ~289 MB
        isRequired = true,
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
