package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Clock screen top app bar — shared between [SidePanelScreen] production code
 * and UI tests. Mirrors the exact structure of the real Clock toolbar:
 * back button, "Clock" title, and three-dot overflow with "Clock settings".
 *
 * @param onBack invoked when the back navigation button is tapped.
 * @param onNavigateToClockSettings invoked when "Clock settings" is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreenTopBar(
    onBack: () -> Unit = {},
    onNavigateToClockSettings: () -> Unit = {},
) {
    var showOverflow by remember { mutableStateOf(false) }
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
            Box {
                IconButton(
                    onClick = { showOverflow = true },
                    modifier = Modifier.testTag("clock_overflow_button"),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Clock options",
                    )
                }
                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Clock settings") },
                        onClick = {
                            showOverflow = false
                            onNavigateToClockSettings()
                        },
                        modifier = Modifier.testTag("clock_overflow_settings"),
                    )
                }
            }
        },
    )
}
