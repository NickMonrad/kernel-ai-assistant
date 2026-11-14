package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Starts the selected Piper voice (Cori by default) when the user chooses Piper, so the voice
 * downloads next to the engine choice instead of after scrolling past the sliders (#1868).
 *
 * Shared by Voice settings and the setup wizard. Progress comes from
 * [SherpaVoicePackDownloadManager.downloadStates]; the caller renders it beside the selection.
 *
 * Network: [SherpaVoicePackDownloadManager.startDownload] applies
 * [com.kernel.ai.core.inference.download.DownloadConsent.effectiveForManualDownload], so until the
 * user chooses a download network the voice downloads on Wi-Fi only.
 */
@Singleton
class PiperVoiceSetup @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager,
) {
    /**
     * Call after the user picks [engine]. When Piper was picked, starts the selected voice if it is
     * missing or its last attempt failed. Returns that voice so the caller can show its progress
     * (including "Waiting for Wi-Fi"), or null when [engine] is not Piper.
     */
    suspend fun onEngineChosen(engine: VoiceOutputEngine): SherpaPiperVoice? {
        if (engine != VoiceOutputEngine.SherpaExperimental) return null
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        val state = voicePackDownloadManager.downloadStates.value[voice] ?: VoicePackDownloadState.NotDownloaded
        if (shouldStartDownload(state)) voicePackDownloadManager.startDownload(voice)
        return voice
    }

    companion object {
        /** A voice is started only when it is missing or its last attempt failed; never while it runs. */
        internal fun shouldStartDownload(state: VoicePackDownloadState): Boolean =
            state is VoicePackDownloadState.NotDownloaded || state is VoicePackDownloadState.Error
    }
}
