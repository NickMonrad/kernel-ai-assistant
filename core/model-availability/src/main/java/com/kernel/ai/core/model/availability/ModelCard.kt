package com.kernel.ai.core.model.availability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Full-size model card used in Model Management and Settings.
 *
 * Shows:
 * - [stateBadge] at top-right
 * - Model name and description
 * - Optional lock icon for gated models
 * @param onPrimaryAction Click handler for the primary action button. Null = no button.
 * @param primaryActionLabel Label for the primary action button. Null = auto from state.
 * @param onSecondaryAction Click handler for a secondary action (e.g. Delete) shown beside
 *   the primary action when the model is downloaded. Null = no secondary button.
 * @param secondaryActionLabel Label for the secondary action button. Ignored when
 *   [onSecondaryAction] is null.
 * @param modifier Modifier for the card.
 */
@Composable
fun ModelCard(
    title: String,
    description: String?,
    state: ModelAvailabilityState,
    showLock: Boolean = false,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val actionLabel = primaryActionLabel ?: defaultActionLabel(state)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (showLock) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Gated model",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StateBadge(state = state)
            }

            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state is ModelAvailabilityState.Preparing) {
                Spacer(Modifier.height(8.dp))
                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress.coerceIn(0f, 1f),
                    label = "downloadProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (onPrimaryAction != null && actionLabel != null) {
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is ModelAvailabilityState.Preparing -> {
                        // Auto-queued: no action button; User-initiated: show cancel
                        if (!state.isAutoQueued) {
                            Button(
                                onClick = { onPrimaryAction?.invoke() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(actionLabel ?: "Cancel")
                            }
                        }
                    }
                    is ModelAvailabilityState.Unavailable,
                    ModelAvailabilityState.NotDisplayed -> {
                        // Full-width outlined button for unavailable/not-displayed
                        OutlinedButton(
                            onClick = onPrimaryAction,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(actionLabel)
                        }
                    }
                    is ModelAvailabilityState.Ready -> {
                        if (onSecondaryAction != null && secondaryActionLabel != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onPrimaryAction,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(actionLabel)
                                }
                                OutlinedButton(onClick = onSecondaryAction) {
                                    Text(secondaryActionLabel)
                                }
                            }
                        } else {
                            Button(
                                onClick = onPrimaryAction,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(actionLabel)
                            }
                        }
                    }
                    is ModelAvailabilityState.ActionRequired -> {
                        Button(
                            onClick = onPrimaryAction,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact variant used in VoiceScreen and Chat onboarding.
 * No action button — just the name, optional description, and state badge.
 */
@Composable
fun ModelCardCompact(
    title: String,
    description: String?,
    state: ModelAvailabilityState,
    showLock: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showLock) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Gated model",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StateBadge(state = state)
        }
        if (state is ModelAvailabilityState.Preparing) {
            val animatedProgress by animateFloatAsState(
                targetValue = state.progress.coerceIn(0f, 1f),
                label = "downloadProgress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Returns a default action label for a given availability state.
 * Used when [ModelCard] is constructed without [primaryActionLabel].
 */
fun defaultActionLabel(state: ModelAvailabilityState): String? = when (state) {
    is ModelAvailabilityState.Ready -> "Update"
    is ModelAvailabilityState.Preparing -> if (state.isAutoQueued) null else "Cancel"
    is ModelAvailabilityState.ActionRequired -> when (state.reason) {
        is ActionReason.SignInRequired -> "Sign in to HuggingFace"
        is ActionReason.LicenseRequired -> "View license"
        is ActionReason.ApprovalPending -> null
        is ActionReason.AccessApprovalRequired -> "Request access"
        is ActionReason.InsufficientStorage -> "Manage storage"
        is ActionReason.DownloadFailed -> "Retry download"
    }
    is ModelAvailabilityState.Unavailable -> null
    ModelAvailabilityState.NotDisplayed -> "Download"
}
