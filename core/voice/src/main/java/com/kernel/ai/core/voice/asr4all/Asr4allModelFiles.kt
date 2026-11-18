package com.kernel.ai.core.voice.asr4all

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import java.io.File
import java.nio.file.Files

/**
 * Maps the six downloaded asr4all files to the release layout the engine loads. The downloads
 * use prefixed names so they do not collide with other models in the shared models folder; the
 * engine needs the upstream names, so this folder links each one back.
 */
object Asr4allModelFiles {
    private val layout: List<Pair<String, KernelModel>> = listOf(
        "metadata.json" to KernelModel.ASR4ALL_METADATA,
        "stream_c16r4.onnx" to KernelModel.ASR4ALL_STREAM_C16R4,
        "stream_c32r8.onnx" to KernelModel.ASR4ALL_STREAM_C32R8,
        "pcec_c16.onnx" to KernelModel.ASR4ALL_PCEC_C16,
        "pcec_c16_flush.onnx" to KernelModel.ASR4ALL_PCEC_C16_FLUSH,
        "asr4all.onnx.data" to KernelModel.ASR4ALL_WEIGHTS,
    )

    /** Every model file the engine needs. */
    val models: List<KernelModel> get() = layout.map { it.second }

    /** True once all six downloads are present and non-empty. */
    fun isDownloaded(context: Context): Boolean = models.all { it.localFile(context).let { f -> f.exists() && f.length() > 0 } }

    /**
     * Builds (or refreshes) the upstream-named folder of symlinks and returns it. Call only when
     * [isDownloaded] is true.
     */
    fun releaseDir(context: Context): File {
        val dir = File(context.filesDir, "asr4all/onnx").apply { mkdirs() }
        for ((upstream, model) in layout) {
            val link = File(dir, upstream).toPath()
            val target = model.localFile(context).toPath()
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) continue
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        return dir
    }
}
