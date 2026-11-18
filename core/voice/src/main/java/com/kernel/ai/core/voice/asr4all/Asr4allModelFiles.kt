package com.kernel.ai.core.voice.asr4all

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import java.io.File
import java.nio.file.Files

/**
 * Maps the six downloaded asr4all files of one size to the release layout the engine loads. The
 * downloads use size-specific prefixed names so S and M never collide in the shared models folder;
 * the engine needs the upstream names, so each size gets its own folder that links them back.
 */
object Asr4allModelFiles {
    enum class Size { S, M }

    /** Size used by the no-argument overloads until the tier choice is made (provisional). */
    val DEFAULT_SIZE: Size = Size.S

    private val layouts: Map<Size, List<Pair<String, KernelModel>>> = mapOf(
        Size.S to listOf(
            "metadata.json" to KernelModel.ASR4ALL_S_METADATA,
            "stream_c16r4.onnx" to KernelModel.ASR4ALL_S_STREAM_C16R4,
            "stream_c32r8.onnx" to KernelModel.ASR4ALL_S_STREAM_C32R8,
            "pcec_c16.onnx" to KernelModel.ASR4ALL_S_PCEC_C16,
            "pcec_c16_flush.onnx" to KernelModel.ASR4ALL_S_PCEC_C16_FLUSH,
            "asr4all.onnx.data" to KernelModel.ASR4ALL_S_WEIGHTS,
        ),
        Size.M to listOf(
            "metadata.json" to KernelModel.ASR4ALL_M_METADATA,
            "stream_c16r4.onnx" to KernelModel.ASR4ALL_M_STREAM_C16R4,
            "stream_c32r8.onnx" to KernelModel.ASR4ALL_M_STREAM_C32R8,
            "pcec_c16.onnx" to KernelModel.ASR4ALL_M_PCEC_C16,
            "pcec_c16_flush.onnx" to KernelModel.ASR4ALL_M_PCEC_C16_FLUSH,
            "asr4all.onnx.data" to KernelModel.ASR4ALL_M_WEIGHTS,
        ),
    )

    /** Every model file the engine needs for [size]. */
    fun models(size: Size): List<KernelModel> = layouts.getValue(size).map { it.second }

    /** Files for [DEFAULT_SIZE]. */
    val models: List<KernelModel> get() = models(DEFAULT_SIZE)

    /** True once all six downloads for [size] are present and non-empty. */
    fun isDownloaded(context: Context, size: Size = DEFAULT_SIZE): Boolean =
        models(size).all { it.localFile(context).let { f -> f.exists() && f.length() > 0 } }

    /**
     * Builds (or refreshes) the upstream-named folder of symlinks for [size] and returns it. Call
     * only when [isDownloaded] is true for that size.
     */
    fun releaseDir(context: Context, size: Size = DEFAULT_SIZE): File {
        val dir = File(context.filesDir, "asr4all/${size.name.lowercase()}/onnx").apply { mkdirs() }
        for ((upstream, model) in layouts.getValue(size)) {
            val link = File(dir, upstream).toPath()
            val target = model.localFile(context).toPath()
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) continue
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        return dir
    }
}
