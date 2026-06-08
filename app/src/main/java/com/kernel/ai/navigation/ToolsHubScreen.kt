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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToRoute: (route: String) -> Unit,
    onOpenExamplePrompt: (prompt: String) -> Unit = {},
) {
    var expandedSectionIds by rememberSaveable { mutableStateOf(setOf<String>()) }
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
            HorizontalDivider()
            LearnSection(
                expandedIds = expandedSectionIds,
                onToggleExpand = { id ->
                    expandedSectionIds = if (id in expandedSectionIds) {
                        expandedSectionIds - id
                    } else {
                        expandedSectionIds + id
                    }
                },
                onOpenPrompt = onOpenExamplePrompt,
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

private data class ToolExamplePrompt(
    val id: String,
    val label: String,
    val prompt: String = label,
)

private data class ToolExampleSection(
    val id: String,
    val title: String,
    val groupTag: String,
    val viewMoreTag: String,
    val defaultExamples: List<ToolExamplePrompt>,
    val moreExamples: List<ToolExamplePrompt>,
)

private val allExampleSections = listOf(
    ToolExampleSection(
        id = "lists",
        title = "Lists",
        groupTag = "tools_examples_group_lists",
        viewMoreTag = "tools_examples_view_more_lists",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_lists_add_milk", "Add milk to my shopping list"),
            ToolExamplePrompt("tools_example_lists_chuck_bananas", "Chuck bananas on the grocery list"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_lists_create", "Create a packing list"),
            ToolExamplePrompt("tools_example_lists_show", "Show my shopping list"),
            ToolExamplePrompt("tools_example_lists_remove", "Remove milk from my shopping list"),
            ToolExamplePrompt("tools_example_lists_bulk", "Add bread, eggs, and cheese to my grocery list"),
        ),
    ),
    ToolExampleSection(
        id = "meal_planning",
        title = "Meal planning",
        groupTag = "tools_examples_group_meal_planning",
        viewMoreTag = "tools_examples_view_more_meal_planning",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_meal_plan_dinners_week", "Plan dinners for this week"),
            ToolExamplePrompt("tools_example_meal_plan_shopping_list", "Make a shopping list for my meal plan"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_meal_plan_lunches", "Plan school lunches for the week"),
            ToolExamplePrompt("tools_example_meal_plan_family", "Suggest family dinners with chicken and rice"),
            ToolExamplePrompt("tools_example_meal_plan_5day", "Make a 5-day meal plan for two adults and two kids"),
            ToolExamplePrompt("tools_example_meal_plan_vegetarian", "Plan vegetarian dinners for next week"),
            ToolExamplePrompt("tools_example_meal_plan_grocery", "Create a grocery list from my meal plan"),
            ToolExamplePrompt("tools_example_meal_plan_pantry", "Suggest meals using what I already have"),
        ),
    ),
    ToolExampleSection(
        id = "notes_memory",
        title = "Notes & memory",
        groupTag = "tools_examples_group_notes_memory",
        viewMoreTag = "tools_examples_view_more_notes_memory",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_notes_note_to_self", "Note to self: the garage code is 1234"),
            ToolExamplePrompt("tools_example_memory_dark_mode", "Remember that I prefer dark mode"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_notes_show", "Show my notes"),
            ToolExamplePrompt("tools_example_memory_coffee", "Remember that my favourite coffee is a flat white"),
            ToolExamplePrompt("tools_example_notes_freyja", "Write down that Freyja needs library books tomorrow"),
            ToolExamplePrompt("tools_example_notes_packing", "Create a note about packing for the school trip"),
        ),
    ),
    ToolExampleSection(
        id = "time_planning",
        title = "Time & planning",
        groupTag = "tools_examples_group_time_planning",
        viewMoreTag = "tools_examples_view_more_time_planning",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_time_timer_10", "Set a timer for 10 minutes"),
            ToolExamplePrompt("tools_example_calendar_soccer_training", "Create a calendar event for soccer training"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_calendar_dentist", "Add dentist appointment next Tuesday"),
            ToolExamplePrompt("tools_example_calendar_assembly", "Schedule school assembly for Friday"),
            ToolExamplePrompt("tools_example_reminder_bins", "Remind me to put the bins out tomorrow at 7pm"),
            ToolExamplePrompt("tools_example_date_diff_christmas", "How many days until Christmas?"),
            ToolExamplePrompt("tools_example_alarm_7am", "Set an alarm for 7am tomorrow"),
            ToolExamplePrompt("tools_example_date_birthday", "Save Mum\u2019s birthday as 12 March"),
            ToolExamplePrompt("tools_example_date_diff_july", "How many weeks until 1 July?"),
        ),
    ),
    ToolExampleSection(
        id = "weather",
        title = "Weather",
        groupTag = "tools_examples_group_weather",
        viewMoreTag = "tools_examples_view_more_weather",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_weather_current", "What\u2019s the weather?"),
            ToolExamplePrompt("tools_example_weather_5_day_forecast", "What\u2019s the 5 day forecast?"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_weather_wellington", "What\u2019s the weather like in Wellington?"),
            ToolExamplePrompt("tools_example_weather_rain_today", "Will it rain today?"),
            ToolExamplePrompt("tools_example_weather_tomorrow", "What\u2019s the weather tomorrow?"),
            ToolExamplePrompt("tools_example_weather_bundaberg", "What\u2019s the forecast for Bundaberg this weekend?"),
            ToolExamplePrompt("tools_example_weather_umbrella", "Do I need an umbrella today?"),
            ToolExamplePrompt("tools_example_weather_hot_tomorrow", "How hot will it be tomorrow?"),
        ),
    ),
    ToolExampleSection(
        id = "people_communication",
        title = "People & communication",
        groupTag = "tools_examples_group_people_communication",
        viewMoreTag = "tools_examples_view_more_people_communication",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_people_email_alex", "Email Alex about the meeting"),
            ToolExamplePrompt("tools_example_people_text_running_late", "Text Sarah that I\u2019m running late"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_people_email_school", "Email school that Freyja is sick today"),
            ToolExamplePrompt("tools_example_people_text_dad", "Text Dad that I\u2019m on my way"),
            ToolExamplePrompt("tools_example_people_call_mum", "Call Mum"),
        ),
    ),
    ToolExampleSection(
        id = "device_media",
        title = "Device & media",
        groupTag = "tools_examples_group_device_media",
        viewMoreTag = "tools_examples_view_more_device_media",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_device_flashlight_on", "Turn on the flashlight"),
            ToolExamplePrompt("tools_example_media_play_music", "Play music"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_device_flashlight_off", "Turn off the flashlight"),
            ToolExamplePrompt("tools_example_media_next_track", "Play the next track"),
            ToolExamplePrompt("tools_example_media_pause", "Pause the music"),
            ToolExamplePrompt("tools_example_media_youtube_bluey", "Play Bluey on YouTube"),
            ToolExamplePrompt("tools_example_media_youtube_music", "Play relaxing music on YouTube Music"),
            ToolExamplePrompt("tools_example_media_open_spotify", "Open Spotify"),
            ToolExamplePrompt("tools_example_media_workout_playlist", "Play my workout playlist"),
        ),
    ),
    ToolExampleSection(
        id = "maps_places",
        title = "Maps & places",
        groupTag = "tools_examples_group_maps_places",
        viewMoreTag = "tools_examples_view_more_maps_places",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_maps_navigate_school", "Navigate to school"),
            ToolExamplePrompt("tools_example_maps_find_coffee", "Find coffee near me"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_maps_navigate_home", "Navigate home"),
            ToolExamplePrompt("tools_example_maps_find_petrol", "Find petrol stations nearby"),
            ToolExamplePrompt("tools_example_maps_find_bunnings", "Find Bunnings on the map"),
            ToolExamplePrompt("tools_example_maps_navigate_pharmacy", "Navigate to the nearest pharmacy"),
            ToolExamplePrompt("tools_example_maps_find_parks", "Find parks near me"),
            ToolExamplePrompt("tools_example_maps_find_supermarket", "Find the closest supermarket"),
        ),
    ),
    ToolExampleSection(
        id = "utilities_conversions",
        title = "Utilities & conversions",
        groupTag = "tools_examples_group_utilities_conversions",
        viewMoreTag = "tools_examples_view_more_utilities_conversions",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_example_convert_cups_ml", "Convert 2 cups to mL"),
            ToolExamplePrompt("tools_example_convert_flour_grams", "Convert 1 cup of flour to grams"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_example_convert_butter", "Convert 200 grams of butter to cups"),
            ToolExamplePrompt("tools_example_convert_currency_aud_nzd", "Convert 20 Australian dollars to New Zealand dollars"),
            ToolExamplePrompt("tools_example_convert_percentage", "What\u2019s 15% of 87?"),
            ToolExamplePrompt("tools_example_convert_distance", "Convert 5 kilometres to miles"),
            ToolExamplePrompt("tools_example_convert_currency_usd_aud", "How much is 50 USD in AUD?"),
        ),
    ),
)

@Composable
private fun LearnSection(
    expandedIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    onOpenPrompt: (String) -> Unit,
) {
    Text(
        text = "Learn what Jandal can do",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("tools_examples_header"),
    )
    Text(
        text = "Examples open in Actions so you can review or edit before running. Some examples may ask a follow-up question.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("tools_examples_helper_copy"),
    )
    Spacer(modifier = Modifier.height(8.dp))

    allExampleSections.forEach { section ->
        val isExpanded = section.id in expandedIds
        Text(
            text = section.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(section.groupTag),
        )

        section.defaultExamples.forEach { example ->
            ToolsExampleRow(
                testTag = example.id,
                label = example.label,
                onClick = { onOpenPrompt(example.prompt) },
            )
        }

        if (isExpanded) {
            section.moreExamples.forEach { example ->
                ToolsExampleRow(
                    testTag = example.id,
                    label = example.label,
                    onClick = { onOpenPrompt(example.prompt) },
                )
            }
            TextButton(
                onClick = { onToggleExpand(section.id) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag(section.viewMoreTag),
            ) {
                Text("Show less")
            }
        } else {
            TextButton(
                onClick = { onToggleExpand(section.id) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag(section.viewMoreTag),
            ) {
                Text("View more")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun ToolsExampleRow(
    testTag: String,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        headlineContent = {
            Text(
                text = "\u2022 $label",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}
