package com.kernel.ai.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val CLOCK_VOICE_FAB_TEST_TAG = "clock_voice_fab"

/**
 * Shared Clock voice action FAB.
 *
 * #1279 standardises the Clock floating action area so every Clock tab uses the
 * same bottom-right voice affordance. Tab-specific primary actions stay inside
 * the tab content or empty-state cards.
 */
@Composable
internal fun ClockVoiceFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.testTag(CLOCK_VOICE_FAB_TEST_TAG),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Voice input")
    }
}
