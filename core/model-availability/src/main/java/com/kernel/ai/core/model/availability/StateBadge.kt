package com.kernel.ai.core.model.availability

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Compact state badge chip for model availability.
 *
 * Uses M3 `AssistChip` shape at 28dp height with an icon + label.
 * Maps [ModelAvailabilityState] to the appropriate icon and color scheme.
 *
 * @param state The availability state to render.
 * @param modifier Modifier for the chip.
 */
@Composable
fun StateBadge(
    state: ModelAvailabilityState,
    modifier: Modifier = Modifier,
) {
    val (label, icon, containerColor, contentColor) = when (state) {
        is ModelAvailabilityState.Ready -> BadgeValues(
            label = "Ready",
            icon = Icons.Default.CheckCircle,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        is ModelAvailabilityState.Preparing -> BadgeValues(
            label = if (state.isAutoQueued) "Waiting" else "Downloading",
            icon = Icons.Default.HourglassEmpty,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        is ModelAvailabilityState.ActionRequired -> BadgeValues(
            label = when (state.reason) {
                is ActionReason.SignInRequired -> "Sign in"
                is ActionReason.LicenseRequired -> "License"
                is ActionReason.ApprovalPending -> "Pending"
                is ActionReason.AccessApprovalRequired -> "Access"
                is ActionReason.InsufficientStorage -> "Storage"
                is ActionReason.DownloadFailed -> "Failed"
            },
            icon = Icons.Default.WarningAmber,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        is ModelAvailabilityState.Unavailable -> BadgeValues(
            label = when (state.reason) {
                is UnavailableReason.AccessDenied -> "Denied"
                is UnavailableReason.ProviderUnavailable -> "Unavailable"
                is UnavailableReason.ModelRemoved -> "Removed"
                is UnavailableReason.UnsupportedDevice -> "Unsupported"
                is UnavailableReason.NotBundled -> "Not available"
            },
            icon = Icons.Default.Block,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelAvailabilityState.NotDisplayed -> return // Don't render a badge
    }

    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.padding(end = 2.dp),
                tint = contentColor,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor,
        ),
        modifier = modifier,
    )
}

private data class BadgeValues(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
)
