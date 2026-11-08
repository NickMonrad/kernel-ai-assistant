package com.kernel.ai.core.model.availability

/**
 * User-facing label and supporting text for each [ModelAvailabilityState].
 *
 * These are plain data values (no Compose dependency). The UI layer reads these
 * to populate text elements — avoids leaking enum names into user-facing copy.
 */
data class AvailabilityStrings(
    val label: String,
    val supportingText: String? = null,
)

fun ModelAvailabilityState.toStrings(): AvailabilityStrings = when (this) {
    is ModelAvailabilityState.Ready -> AvailabilityStrings(
        label = "Ready",
        supportingText = null,
    )
    is ModelAvailabilityState.Preparing -> AvailabilityStrings(
        label = if (isAutoQueued) "Waiting" else "Downloading",
        supportingText = if (isAutoQueued) "Starting soon…" else null,
    )
    is ModelAvailabilityState.ActionRequired -> when (reason) {
        is ActionReason.SignInRequired -> AvailabilityStrings(
            label = "Sign in required",
            supportingText = "Sign in to HuggingFace to download this model",
        )
        is ActionReason.LicenseRequired -> AvailabilityStrings(
            label = "License required",
            supportingText = "Accept the model license on HuggingFace",
        )
        is ActionReason.ApprovalPending -> AvailabilityStrings(
            label = "Approval pending",
            supportingText = "Waiting for HuggingFace moderation",
        )
        is ActionReason.AccessApprovalRequired -> AvailabilityStrings(
            label = "Access request required",
            supportingText = "Request access on ${reason.providerName}",
        )
        is ActionReason.InsufficientStorage -> AvailabilityStrings(
            label = "Insufficient storage",
            supportingText = "Free up space to download this model",
        )
        is ActionReason.DownloadFailed -> AvailabilityStrings(
            label = "Download failed",
            supportingText = reason.message,
        )
    }
    is ModelAvailabilityState.Unavailable -> when (reason) {
        is UnavailableReason.AccessDenied -> AvailabilityStrings(
            label = "Access denied",
            supportingText = "Your access request was denied by the provider",
        )
        is UnavailableReason.ProviderUnavailable -> AvailabilityStrings(
            label = "Provider unavailable",
            supportingText = "The model provider is temporarily unavailable",
        )
        is UnavailableReason.ModelRemoved -> AvailabilityStrings(
            label = "Model removed",
            supportingText = "This model has been removed from the provider",
        )
        is UnavailableReason.UnsupportedDevice -> AvailabilityStrings(
            label = "Unsupported device",
            supportingText = reason.message,
        )
        is UnavailableReason.NotBundled -> AvailabilityStrings(
            label = "Not available",
            supportingText = "This model is not bundled with the app",
        )
    }
    ModelAvailabilityState.NotDisplayed -> AvailabilityStrings(
        label = "",
        supportingText = null,
    )
}
