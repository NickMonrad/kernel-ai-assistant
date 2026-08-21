package com.kernel.ai.core.voice

import android.content.Context
import java.io.File

/** Debug-only Inflect Micro model pair managed from Voice settings. */
object InflectMicroModelSpec {
    data class RequiredModel(
        val fileName: String,
        val displayName: String,
    )

    val requiredModels: List<RequiredModel> = listOf(
        RequiredModel("duration.onnx", "Inflect Micro duration graph"),
        RequiredModel("decode.onnx", "Inflect Micro decode graph"),
    )

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
