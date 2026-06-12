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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

data class ToolExamplePrompt(
    val id: String,
    val label: String,
    val prompt: String = label,
)

data class ToolExampleSection(
    val id: String,
    val title: String,
    val groupTag: String,
    val viewMoreTag: String,
    val defaultExamples: List<ToolExamplePrompt>,
    val moreExamples: List<ToolExamplePrompt>,
)

val allExampleSections = listOf(
    ToolExampleSection(
        id = "lists",
        title = "Lists",
        groupTag = "tools_learn_group_lists",
        viewMoreTag = "tools_learn_view_more_lists",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_lists_add_milk", "Add milk to my shopping list"),
            ToolExamplePrompt("tools_learn_lists_chuck_bananas", "Chuck bananas on the grocery list"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_lists_create", "Create a packing list"),
            ToolExamplePrompt("tools_learn_lists_show", "Show my shopping list"),
            ToolExamplePrompt("tools_learn_lists_remove", "Remove milk from my shopping list"),
            ToolExamplePrompt("tools_learn_lists_bulk", "Add bread, eggs, and cheese to my grocery list"),
        ),
    ),
    ToolExampleSection(
        id = "meal_planning",
        title = "Meal planning",
        groupTag = "tools_learn_group_meal_planning",
        viewMoreTag = "tools_learn_view_more_meal_planning",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_meal_plan_dinners_week", "Plan dinners for this week"),
            ToolExamplePrompt("tools_learn_meal_plan_shopping_list", "Make a shopping list for my meal plan"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_meal_plan_lunches", "Plan school lunches for the week"),
            ToolExamplePrompt("tools_learn_meal_plan_family", "Suggest family dinners with chicken and rice"),
            ToolExamplePrompt("tools_learn_meal_plan_5day", "Make a 5-day meal plan for two adults and two kids"),
            ToolExamplePrompt("tools_learn_meal_plan_vegetarian", "Plan vegetarian dinners for next week"),
            ToolExamplePrompt("tools_learn_meal_plan_grocery", "Create a grocery list from my meal plan"),
            ToolExamplePrompt("tools_learn_meal_plan_pantry", "Suggest meals using what I already have"),
        ),
    ),
    ToolExampleSection(
        id = "notes_memory",
        title = "Notes & memory",
        groupTag = "tools_learn_group_notes_memory",
        viewMoreTag = "tools_learn_view_more_notes_memory",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_notes_note_to_self", "Note to self: the garage code is 1234"),
            ToolExamplePrompt("tools_learn_memory_dark_mode", "Remember that I prefer dark mode"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_notes_show", "Show my notes"),
            ToolExamplePrompt("tools_learn_memory_coffee", "Remember that my favourite coffee is a flat white"),
            ToolExamplePrompt("tools_learn_notes_freyja", "Write down that Freyja needs library books tomorrow"),
            ToolExamplePrompt("tools_learn_notes_packing", "Create a note about packing for the school trip"),
        ),
    ),
    ToolExampleSection(
        id = "time_planning",
        title = "Time & planning",
        groupTag = "tools_learn_group_time_planning",
        viewMoreTag = "tools_learn_view_more_time_planning",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_time_timer_10", "Set a timer for 10 minutes"),
            ToolExamplePrompt("tools_learn_calendar_soccer_training", "Create a calendar event for soccer training"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_calendar_dentist", "Add dentist appointment next Tuesday"),
            ToolExamplePrompt("tools_learn_calendar_assembly", "Schedule school assembly for Friday"),
            ToolExamplePrompt("tools_learn_reminder_bins", "Remind me to put the bins out tomorrow at 7pm"),
            ToolExamplePrompt("tools_learn_date_diff_christmas", "How many days until Christmas?"),
            ToolExamplePrompt("tools_learn_alarm_7am", "Set an alarm for 7am tomorrow"),
            ToolExamplePrompt("tools_learn_date_birthday", "Save Mum's birthday as 12 March"),
            ToolExamplePrompt("tools_learn_date_diff_july", "How many weeks until 1 July?"),
        ),
    ),
    ToolExampleSection(
        id = "weather",
        title = "Weather",
        groupTag = "tools_learn_group_weather",
        viewMoreTag = "tools_learn_view_more_weather",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_weather_current", "What's the weather?"),
            ToolExamplePrompt("tools_learn_weather_5_day_forecast", "What's the 5 day forecast?"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_weather_wellington", "What's the weather like in Wellington?"),
            ToolExamplePrompt("tools_learn_weather_rain_today", "Will it rain today?"),
            ToolExamplePrompt("tools_learn_weather_tomorrow", "What's the weather tomorrow?"),
            ToolExamplePrompt("tools_learn_weather_bundaberg", "What's the forecast for Bundaberg this weekend?"),
            ToolExamplePrompt("tools_learn_weather_umbrella", "Do I need an umbrella today?"),
            ToolExamplePrompt("tools_learn_weather_hot_tomorrow", "How hot will it be tomorrow?"),
        ),
    ),
    ToolExampleSection(
        id = "people_communication",
        title = "People & communication",
        groupTag = "tools_learn_group_people_communication",
        viewMoreTag = "tools_learn_view_more_people_communication",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_people_email_alex", "Email Alex about the meeting"),
            ToolExamplePrompt("tools_learn_people_text_running_late", "Text Sarah that I'm running late"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_people_email_school", "Email school that Freyja is sick today"),
            ToolExamplePrompt("tools_learn_people_text_dad", "Text Dad that I'm on my way"),
            ToolExamplePrompt("tools_learn_people_call_mum", "Call Mum"),
        ),
    ),
    ToolExampleSection(
        id = "device_media",
        title = "Device & media",
        groupTag = "tools_learn_group_device_media",
        viewMoreTag = "tools_learn_view_more_device_media",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_device_flashlight_on", "Turn on the flashlight"),
            ToolExamplePrompt("tools_learn_media_play_music", "Play music"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_device_flashlight_off", "Turn off the flashlight"),
            ToolExamplePrompt("tools_learn_media_next_track", "Play the next track"),
            ToolExamplePrompt("tools_learn_media_pause", "Pause the music"),
            ToolExamplePrompt("tools_learn_media_youtube_bluey", "Play Bluey on YouTube"),
            ToolExamplePrompt("tools_learn_media_youtube_music", "Play relaxing music on YouTube Music"),
            ToolExamplePrompt("tools_learn_media_open_spotify", "Open Spotify"),
            ToolExamplePrompt("tools_learn_media_workout_playlist", "Play my workout playlist"),
        ),
    ),
    ToolExampleSection(
        id = "maps_places",
        title = "Maps & places",
        groupTag = "tools_learn_group_maps_places",
        viewMoreTag = "tools_learn_view_more_maps_places",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_maps_navigate_school", "Navigate to school"),
            ToolExamplePrompt("tools_learn_maps_find_coffee", "Find coffee near me"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_maps_navigate_home", "Navigate home"),
            ToolExamplePrompt("tools_learn_maps_find_petrol", "Find petrol stations nearby"),
            ToolExamplePrompt("tools_learn_maps_find_bunnings", "Find Bunnings on the map"),
            ToolExamplePrompt("tools_learn_maps_navigate_pharmacy", "Navigate to the nearest pharmacy"),
            ToolExamplePrompt("tools_learn_maps_find_parks", "Find parks near me"),
            ToolExamplePrompt("tools_learn_maps_find_supermarket", "Find the closest supermarket"),
        ),
    ),
    ToolExampleSection(
        id = "utilities_conversions",
        title = "Utilities & conversions",
        groupTag = "tools_learn_group_utilities_conversions",
        viewMoreTag = "tools_learn_view_more_utilities_conversions",
        defaultExamples = listOf(
            ToolExamplePrompt("tools_learn_convert_cups_ml", "Convert 2 cups to mL"),
            ToolExamplePrompt("tools_learn_convert_flour_grams", "Convert 1 cup of flour to grams"),
        ),
        moreExamples = listOf(
            ToolExamplePrompt("tools_learn_convert_butter", "Convert 200 grams of butter to cups"),
            ToolExamplePrompt("tools_learn_convert_currency_aud_nzd", "Convert 20 Australian dollars to New Zealand dollars"),
            ToolExamplePrompt("tools_learn_convert_percentage", "What's 15% of 87?"),
            ToolExamplePrompt("tools_learn_convert_distance", "Convert 5 kilometres to miles"),
            ToolExamplePrompt("tools_learn_convert_currency_usd_aud", "How much is 50 USD in AUD?"),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsLearnScreen(
    onBack: () -> Unit,
    onOpenPrompt: (String) -> Unit,
) {
    var expandedSectionIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learn what Jandal can do") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_tools_learn")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
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
                .testTag("tools_learn_screen"),
        ) {
            Text(
                text = "Example prompts for actions, planning, weather, maps, media, and more",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("tools_learn_helper_copy"),
            )
            Text(
                text = "Examples open in Actions so you can review or edit before running. Some examples may ask a follow-up question.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("tools_learn_privacy_note"),
            )
            Spacer(modifier = Modifier.height(8.dp))

            allExampleSections.forEach { section ->
                val isExpanded = section.id in expandedSectionIds
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
                        onClick = { expandedSectionIds = expandedSectionIds - section.id },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag(section.viewMoreTag),
                    ) {
                        Text("Show less")
                    }
                } else {
                    TextButton(
                        onClick = { expandedSectionIds = expandedSectionIds + section.id },
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
