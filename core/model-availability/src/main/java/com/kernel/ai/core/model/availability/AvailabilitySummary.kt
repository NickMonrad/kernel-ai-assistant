package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

/**
 * Summary counts of models in each [ModelAvailabilityState].
 */
data class AvailabilitySummary(
    val total: Int,
    val ready: Int = 0,
    val preparing: Int = 0,
    val actionRequired: Int = 0,
    val unavailable: Int = 0,
) {
    val displaySummary: String get() {
        return "$ready of $total models available"
    }
}

/**
 * Computes an [AvailabilitySummary] from a list of models and their download states.
 */
fun computeAvailabilitySummary(
    models: List<KernelModel>,
    downloadStates: Map<KernelModel, DownloadState>,
    hfAuth: Boolean,
    downloadSources: Map<KernelModel, DownloadSource> = emptyMap(),
    gatedStatuses: Map<KernelModel, GatedModelStatus> = emptyMap(),
): AvailabilitySummary {
    var ready = 0
    var preparing = 0
    var actionRequired = 0
    var unavailable = 0

    for (model in models) {
        val state = downloadStates[model] ?: DownloadState.NotDownloaded
        val source = downloadSources[model] ?: DownloadSource.USER_INITIATED
        val gatedStatus = gatedStatuses[model] ?: GatedModelStatus.NONE
        val availability = state.toAvailability(
            model = model,
            hfAuth = hfAuth,
            source = source,
            gated = gatedStatus,
        )
        when (availability) {
            is ModelAvailabilityState.Ready -> ready++
            is ModelAvailabilityState.Preparing -> preparing++
            is ModelAvailabilityState.ActionRequired -> actionRequired++
            is ModelAvailabilityState.Unavailable -> unavailable++
            ModelAvailabilityState.NotDisplayed -> {} // NotDisplayed = no badge shown
        }
    }

    return AvailabilitySummary(
        total = models.size,
        ready = ready,
        preparing = preparing,
        actionRequired = actionRequired,
        unavailable = unavailable,
    )
}
