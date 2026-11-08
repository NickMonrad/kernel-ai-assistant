package com.kernel.ai.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Clock screen top app bar — shared between [SidePanelScreen] production code
 * and UI tests. Displays a back button, "Clock" title, and a settings cog
 * that navigates directly to Clock settings.
 *
 * @param onBack invoked when the back navigation button is tapped.
 * @param onNavigateToClockSettings invoked when the settings cog is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreenTopBar(
    onBack: () -> Unit = {},
    onNavigateToClockSettings: () -> Unit = {},
) {
    TopAppBar(
        title = { Text("Clock") },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("back_button"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = {
            IconButton(
                onClick = onNavigateToClockSettings,
                modifier = Modifier.testTag("clock_settings_button"),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Clock settings",
                )
            }
        },
    )
}
