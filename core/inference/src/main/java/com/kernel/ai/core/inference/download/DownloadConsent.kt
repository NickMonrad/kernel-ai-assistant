package com.kernel.ai.core.inference.download

import androidx.work.NetworkType
import androidx.work.WorkInfo
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

    /** Voice defaults (Piper voice, Zipformer STT) wait for the same consent as the model auto-queue (#1855). */
    fun voiceDefaultsAllowed(choice: DownloadNetworkChoice?): Boolean = autoQueueAllowed(choice)

    /**
     * Unfinished work is re-enqueued when the choice changes between two known values. Null is never
     * a change to act on: before the first choice nothing is queued, and an auto-queue is started by
     * the caller's own check. Re-enqueue uses REPLACE so the new network constraint applies.
     */
    fun shouldRequeueOnChoiceChange(previous: DownloadNetworkChoice?, next: DownloadNetworkChoice?): Boolean =
        previous != null && next != null && previous != next

    /** True when any job in [states] is queued, running or blocked. */
    fun hasUnfinishedWork(states: Collection<WorkInfo.State>): Boolean = states.any { !it.isFinished }

    /**
     * Updated installs (last update later than first install) keep their current behaviour: any
     * connection, no prompt. Fresh installs stay null until the user chooses in the wizard (#1855).
     * Decided from the install record, never from queued work, so a fresh install with no choice
     * cannot be mistaken for an existing one.
     */
    fun legacyChoiceFor(isUpdatedInstall: Boolean): DownloadNetworkChoice? =
        if (isUpdatedInstall) DownloadNetworkChoice.MOBILE_DATA_ALLOWED else null

    /**
     * Network for a download the user starts directly. Before any choice exists the safe default is
     * Wi-Fi only, so a manual press on mobile data waits for Wi-Fi and offers "Use mobile data".
     */
    fun effectiveForManualDownload(choice: DownloadNetworkChoice?): DownloadNetworkChoice =
        choice ?: DownloadNetworkChoice.WIFI_ONLY

    /**
     * The single definition of what auto-queue starts: required models (minus Gemma E2B on flagship)
     * plus tier-preferred optional models that the user has not suppressed. Gated models need a token.
     * Used by the auto-queue and by the wizard's size estimate so they cannot drift apart.
     */
    fun autoQueueModels(
        tier: HardwareTier,
        isDownloaded: (KernelModel) -> Boolean,
        suppressedIds: Set<String>,
        hasToken: Boolean,
    ): List<KernelModel> = KernelModel.entries.filter { model ->
        !model.isBundled && !isDownloaded(model) && (!model.isGated || hasToken) && when {
            model.isRequired -> !(model == KernelModel.GEMMA_4_E2B && tier == HardwareTier.FLAGSHIP)
            else -> model.preferredForTier == tier && model.name !in suppressedIds
        }
    }

    /**
     * Speech input models the voice defaults queue for the default Auto engine (Zipformer files).
     * Matched by file name, as the voice provisioner does.
     */
    fun speechDefaultModels(isDownloaded: (KernelModel) -> Boolean): List<KernelModel> =
        KernelModel.entries.filter { it.fileName.startsWith("sherpa-stt-") && !isDownloaded(it) }

    /**
     * Total the wizard shows as "About X": the planned models (including the Zipformer files) plus the
     * planned voice packs (for example Cori). Callers pass the same lists the queue will start.
     */
    fun firstRunEstimateBytes(models: List<KernelModel>, voiceBytes: Long): Long =
        models.sumOf { it.approxSizeBytes } + voiceBytes
}
