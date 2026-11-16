package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager

/**
 * Shared Inflect Micro download plumbing for Settings > Voice and the setup wizard (#1936, #1937).
 * Both entry points queue the same two graph files plus the Piper pack the frontend needs.
 */
object InflectMicroSetup {

    /** Catalogue entries for the Inflect Micro graph files. */
    fun kernelModels(): List<KernelModel> =
        InflectMicroModelSpec.requiredModels.map { required ->
            KernelModel.entries.first { it.fileName == required.fileName }
        }

    /**
     * Starts downloads for any Inflect graph file that is not downloaded or in flight, then starts the
     * [frontendVoice] pack when it is not downloaded or failed. Files already downloading are left alone.
     */
    fun queue(
        modelDownloadManager: ModelDownloadManager,
        voicePackDownloadManager: SherpaVoicePackDownloadManager,
        frontendVoice: SherpaPiperVoice,
    ) {
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

        val frontendState =
            voicePackDownloadManager.downloadStates.value[frontendVoice]
                ?: VoicePackDownloadState.NotDownloaded
        if (frontendState is VoicePackDownloadState.NotDownloaded ||
            frontendState is VoicePackDownloadState.Error
        ) {
            voicePackDownloadManager.startDownload(frontendVoice)
        }
    }
}
