package com.kernel.ai.core.voice

import android.content.Context
import android.os.Build
import com.kernel.ai.core.inference.hardware.HardwareTier
import java.io.File

/** Inflect Micro model pair managed from Voice settings on eligible devices. */
object InflectMicroModelSpec {
    data class RequiredModel(
        val fileName: String,
        val displayName: String,
    )

    val requiredModels: List<RequiredModel> = listOf(
        RequiredModel("duration.onnx", "Inflect Micro duration graph"),
        RequiredModel("decode.onnx", "Inflect Micro decode graph"),
    )

    /**
     * The pinned Inflect JNI extension is present only in the arm64-v8a AAR payload.
     */
    fun isReleaseEligible(
        tier: HardwareTier,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
    ): Boolean = tier == HardwareTier.FLAGSHIP &&
        supportedAbis.any { it == "arm64-v8a" }

    fun modelDirectory(context: Context): File {
        val modelsDirectory = context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")
        modelsDirectory.mkdirs()
        return modelsDirectory
    }

    fun isDownloaded(context: Context): Boolean =
        requiredModels.all { model ->
            modelFile(context, model).let { it.isFile && it.length() > 0L }
        }

    fun modelFile(context: Context, model: RequiredModel): File =
        File(modelDirectory(context), model.fileName)
}
