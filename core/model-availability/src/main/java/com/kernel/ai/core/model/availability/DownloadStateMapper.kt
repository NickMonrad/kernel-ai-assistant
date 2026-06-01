package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

/**
 * Maps the core [DownloadState] to the UI-layer [ModelAvailabilityState].
 *
 * Truth table (see docs/model-availability-ux-patterns.md):
 *
 * | DownloadState        | isBundled | isGated | hfAuth | source       | gated           | → Result                          |
 * |----------------------|-----------|---------|--------|--------------|-----------------|-----------------------------------|
 * | Downloaded(*)        | any       | any     | any    | any          | any             | Ready                             |
 * | NotDownloaded        | true      | any     | any    | any          | any             | Ready                             |
 * | Downloading(p)       | any       | any     | any    | any          | any             | Preparing(p, source == AUTO_QUEUED)|
 * | NotDownloaded        | false     | true    | false  | any          | any             | ActionRequired(SignInRequired)    |
 * | NotDownloaded        | false     | true    | true   | any          | APPROVAL_PENDING | ActionRequired(ApprovalPending)  |
 * | NotDownloaded        | false     | true    | true   | any          | ACCESS_DENIED   | Unavailable(AccessDenied)         |
 * | NotDownloaded        | false     | false   | any    | AUTO_QUEUED  | any             | Preparing(0f, isAutoQueued = true)|
 * | NotDownloaded        | false     | false   | any    | USER_INITIATED| any             | (no badge — primary action only)  |
 * | Error(licence=T)     | any       | any     | any    | any          | any             | ActionRequired(LicenseRequired)   |
 * | Error(message)       | any       | any     | any    | any          | any             | ActionRequired(DownloadFailed(msg))|
 */
fun DownloadState.toAvailability(
    model: KernelModel,
    hfAuth: Boolean,
    source: DownloadSource = DownloadSource.USER_INITIATED,
    gated: GatedModelStatus = GatedModelStatus.NONE,
): ModelAvailabilityState {
    return when (this) {
    is DownloadState.Downloaded -> ModelAvailabilityState.Ready
    is DownloadState.Downloading -> ModelAvailabilityState.Preparing(
        progress = progress,
        isAutoQueued = source == DownloadSource.AUTO_QUEUED,
    )
    is DownloadState.NotDownloaded -> {
        if (model.isBundled) return ModelAvailabilityState.Ready
        if (model.isGated) {
            if (!hfAuth) return ModelAvailabilityState.ActionRequired(ActionReason.SignInRequired)
            return when (gated) {
                GatedModelStatus.APPROVAL_PENDING -> ModelAvailabilityState.ActionRequired(ActionReason.ApprovalPending)
                GatedModelStatus.ACCESS_DENIED -> ModelAvailabilityState.Unavailable(UnavailableReason.AccessDenied)
                else -> ModelAvailabilityState.NotDisplayed
            }
        }
        // Ungated model — source determines display
        when (source) {
            DownloadSource.AUTO_QUEUED -> ModelAvailabilityState.Preparing(
                progress = 0f,
                isAutoQueued = true,
            )
            DownloadSource.USER_INITIATED -> ModelAvailabilityState.NotDisplayed
        }
    }
    is DownloadState.Error -> {
        if (licenceRequired) {
            ModelAvailabilityState.ActionRequired(ActionReason.LicenseRequired)
        } else {
            ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed(message))
        }
    }
}
}