package com.kernel.ai.core.model.availability

/**
 * Canonical 4-state model for model availability in the UI layer.
 *
 * Mapped from [com.kernel.ai.core.inference.download.DownloadState] via
 * [DownloadStateMapper.toAvailability].
 *
 * States:
 * - [Ready]: Model is on disk and ready for inference.
 * - [Preparing]: Download is in progress or auto-queued.
 * - [ActionRequired]: User must take an action (sign in, accept licence, etc.).
 * - [Unavailable]: Model cannot be used on this device or at this time.
 */
sealed class ModelAvailabilityState {
    data object Ready : ModelAvailabilityState()
    data class Preparing(
        val progress: Float = 0f,
        val isAutoQueued: Boolean = false,
    ) : ModelAvailabilityState()
    data class ActionRequired(val reason: ActionReason) : ModelAvailabilityState()
    data class Unavailable(val reason: UnavailableReason) : ModelAvailabilityState()
    /** Internal sentinel — the mapper returns this when no badge should be shown. */
    internal data object NotDisplayed : ModelAvailabilityState()
}

sealed class ActionReason {
    data object SignInRequired : ActionReason()
    data object LicenseRequired : ActionReason()
    data class AccessApprovalRequired(val providerName: String) : ActionReason()
    data object ApprovalPending : ActionReason()
    data object InsufficientStorage : ActionReason()
    data class DownloadFailed(val message: String) : ActionReason()
}

sealed class UnavailableReason {
    data object AccessDenied : UnavailableReason()
    data object ProviderUnavailable : UnavailableReason()
    data object ModelRemoved : UnavailableReason()
    data class UnsupportedDevice(val message: String) : UnavailableReason()
    data object NotBundled : UnavailableReason()
}
