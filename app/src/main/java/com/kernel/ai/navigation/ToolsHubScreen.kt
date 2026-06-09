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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
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
            ToolsListItem(
                testTag = "tools_row_learn",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "Learn what Jandal can do",
                subtitle = "Example prompts for actions, planning, weather, maps, media, and more",
                onClick = { onNavigateToRoute(ROUTE_TOOLS_LEARN) },
            )
            HorizontalDivider()

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
                onClick = { onNavigateToRoute(ROUTE_LISTS) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_notes",
                icon = Icons.Default.Note,
                title = "Notes",
                subtitle = "Quick notes and saved thoughts",
                onClick = { onNavigateToRoute(ROUTE_NOTES) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_meal_plans",
                icon = Icons.Default.Bookmarks,
                title = "Meal plans",
                subtitle = "Plan meals and generate shopping ideas",
                onClick = { onNavigateToRoute(ROUTE_MEAL_PLANS) },
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
                onClick = { onNavigateToRoute(ROUTE_SIDE_PANEL) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_important_dates",
                icon = Icons.Default.Event,
                title = "Important dates",
                subtitle = "Birthdays, anniversaries, and recurring dates",
                onClick = { onNavigateToRoute(ROUTE_IMPORTANT_DATES) },
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
                onClick = { onNavigateToRoute(ROUTE_CONTACT_ALIASES) },
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
                onClick = { onNavigateToRoute(ROUTE_CONVERT) },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Personalisation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_group_personalisation"),
            )
            ToolsListItem(
                testTag = "tools_row_user_profile",
                icon = Icons.Default.Person,
                title = "User Profile",
                subtitle = "Tell Jandal about yourself",
                onClick = { onNavigateToRoute(ROUTE_USER_PROFILE) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_memory",
                icon = Icons.Default.Bookmarks,
                title = "Memory",
                subtitle = "Manage stored memories",
                onClick = { onNavigateToRoute(ROUTE_MEMORY) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_voice",
                icon = Icons.Default.Tune,
                title = "Voice",
                subtitle = "Speech and spoken response settings",
                onClick = { onNavigateToRoute(ROUTE_VOICE) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_chat_preferences",
                icon = Icons.Default.Forum,
                title = "Chat Preferences",
                subtitle = "Archive, themes, wallpaper, and copy options",
                onClick = { onNavigateToRoute(ROUTE_CHAT_PREFERENCES) },
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
                onClick = { onNavigateToRoute(ROUTE_SETTINGS) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_models",
                icon = Icons.Default.SmartToy,
                title = "Models",
                subtitle = "Downloads, availability, and inference preferences",
                onClick = { onNavigateToRoute(buildModelManagementRoute()) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_permissions",
                icon = Icons.Default.Security,
                title = "Permissions",
                subtitle = "Review Android permissions used by Jandal",
                onClick = { onNavigateToRoute(ROUTE_APP_PERMISSIONS) },
            )
            HorizontalDivider()
            ToolsListItem(
                testTag = "tools_row_about",
                icon = Icons.Default.Info,
                title = "About",
                subtitle = "Build info and debug tools",
                onClick = { onNavigateToRoute(ROUTE_ABOUT) },
            )
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