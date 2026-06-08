package com.kernel.ai.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private const val DEFAULT_MODEL_MANAGEMENT_ROUTE = "settings/model_management?scrollTo=false"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToRoute: (route: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("tools_screen"),
        ) {
            Text(
                text = "Productivity",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_productivity"),
            )
            ToolsListItem(
                testTag = "tools_row_lists",
                icon = Icons.Default.Checklist,
                title = "Lists",
                subtitle = "Shopping, tasks, and reusable lists",
                onClick = { onNavigateToRoute("lists") },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_notes",
                icon = Icons.Default.Note,
                title = "Notes",
                subtitle = "Quick notes and saved thoughts",
                onClick = { onNavigateToRoute("settings/notes") },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_meal_plans",
                icon = Icons.Default.Bookmarks,
                title = "Meal plans",
                subtitle = "Plan meals and generate shopping ideas",
                onClick = { onNavigateToRoute("meal_plans") },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Time & planning",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_time_planning"),
            )
            ToolsListItem(
                testTag = "tools_row_clock",
                icon = Icons.Default.Timer,
                title = "Clock",
                subtitle = "Alarms, timers, stopwatch, and world clock",
                onClick = { onNavigateToRoute("settings/side_panel") },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_important_dates",
                icon = Icons.Default.Event,
                title = "Important dates",
                subtitle = "Birthdays, anniversaries, and recurring dates",
                onClick = { onNavigateToRoute("settings/important_dates") },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "People",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_people"),
            )
            ToolsListItem(
                testTag = "tools_row_people_contacts",
                icon = Icons.Default.People,
                title = "People & Contacts",
                subtitle = "Contact aliases and people Jandal can recognise",
                onClick = { onNavigateToRoute("settings/contact_aliases") },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Utilities",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_utilities"),
            )
            ToolsListItem(
                testTag = "tools_row_convert",
                icon = Icons.Default.Calculate,
                title = "Convert",
                subtitle = "Units, currency, and quick calculations",
                onClick = { onNavigateToRoute("convert") },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "App setup",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_app_setup"),
            )
            ToolsListItem(
                testTag = "tools_row_settings",
                icon = Icons.Default.Settings,
                title = "Settings",
                subtitle = "App preferences and configuration",
                onClick = { onNavigateToRoute("settings") },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_voice",
                icon = Icons.Default.Tune,
                title = "Voice",
                subtitle = "Speech and spoken response settings",
                onClick = { onNavigateToRoute("settings/voice") },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_models",
                icon = Icons.Default.SmartToy,
                title = "Models",
                subtitle = "Downloads, availability, and inference preferences",
                onClick = { onNavigateToRoute(DEFAULT_MODEL_MANAGEMENT_ROUTE) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_permissions",
                icon = Icons.Default.Security,
                title = "Permissions",
                subtitle = "Review Android permissions used by Jandal",
                onClick = { onNavigateToRoute("settings/app_permissions") },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ToolsListItem(
    testTag: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
    )
}
