package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile

/**
 * The on-demand Kokoro pack (#2117): one download for every file the shipped voices need, so picking a voice never
 * downloads anything. Settings › Voice owns this; the pack is never bundled in the APK.
 */
object KokoroSetup {
    /** Every catalogue entry in the pack: shared programs and tables, both lexicons, and all shipped voices. */
    fun kernelModels(): List<KernelModel> = KokoroModelFiles.allFiles()

    /** Total download size in bytes, for the size chip. */
    fun approxSizeBytes(): Long = kernelModels().sumOf { it.approxSizeBytes }

    /** Pack size text shared by the Settings chip and the Kokoro card description, so both agree (#2170). */
    fun approxSizeText(): String = formatSizeMb(approxSizeBytes())

    /** Decimal MB, the same rule as Settings' formatBytes, so the chip and the description round alike. */
    internal fun formatSizeMb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

    fun isDownloaded(context: Context): Boolean = KokoroModelFiles.allFiles().all { model ->
        model.localFile(context).let { it.isFile && it.length() > 0L }
    }

    /** Starts downloads for every pack file that is not downloaded or in flight. Files already in flight are left alone. */
    fun queue(modelDownloadManager: ModelDownloadManager) {
        val currentStates = modelDownloadManager.downloadStates.value
        kernelModels()
            .filter { model ->
                when (currentStates[model] ?: DownloadState.NotDownloaded) {
                    is DownloadState.NotDownloaded,
                    is DownloadState.Error,
                    -> true
                    is DownloadState.Downloading,
                    is DownloadState.Downloaded,
                    -> false
                }
            }
            .forEach { modelDownloadManager.startDownload(it) }
    }
}
