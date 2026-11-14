package com.kernel.ai.core.inference.download

import androidx.work.NetworkType
import com.kernel.ai.core.inference.hardware.HardwareTier

/**
 * Which networks a download may use. Chosen once in the setup wizard (or Settings) and applied to
 * every download: auto-queued models, manual model downloads and voice packs.
 */
enum class DownloadNetworkChoice(val requiredNetworkType: NetworkType) {
    /** Only unmetered networks (Wi-Fi). The default the wizard preselects. */
    WIFI_ONLY(NetworkType.UNMETERED),

    /** Any connection, including mobile data. */
    MOBILE_DATA_ALLOWED(NetworkType.CONNECTED);

    companion object {
        /** Maps a stored name to a choice. Null (not chosen yet, or unknown) means no consent. */
        fun fromStorageName(name: String?): DownloadNetworkChoice? = entries.firstOrNull { it.name == name }
    }
}

/** Pure consent rules shared by [ModelDownloadManager] and the voice pack download manager. */
object DownloadConsent {

    /** Auto-queued downloads (required and tier models) run only after the user has chosen a network. */
    fun autoQueueAllowed(choice: DownloadNetworkChoice?): Boolean = choice != null

    /**
     * Existing installs that already have model files or unfinished download work keep their
     * current behaviour (any connection, no prompt). New installs stay null until the user chooses.
     */
    fun legacyChoiceFor(hasExistingDownloads: Boolean): DownloadNetworkChoice? =
        if (hasExistingDownloads) DownloadNetworkChoice.MOBILE_DATA_ALLOWED else null

    /**
     * Network for a download the user starts directly. Before any choice exists the safe default is
     * Wi-Fi only, so a manual press on mobile data waits for Wi-Fi and offers "Use mobile data".
     */
    fun effectiveForManualDownload(choice: DownloadNetworkChoice?): DownloadNetworkChoice =
        choice ?: DownloadNetworkChoice.WIFI_ONLY

    /**
     * Models the first-run download plan covers for [tier]. Mirrors the auto-queue filter, so the
     * wizard's size estimate matches what actually downloads.
     */
    fun plannedModels(tier: HardwareTier): List<KernelModel> = KernelModel.entries.filter { model ->
        if (model.isBundled) return@filter false
        if (model.isRequired) {
            !(model == KernelModel.GEMMA_4_E2B && tier == HardwareTier.FLAGSHIP)
        } else {
            model.preferredForTier == tier
        }
    }
}
