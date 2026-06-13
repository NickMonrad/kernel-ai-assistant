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

/**
 * Section order for the Learn screen — derived from the shared [allLearnExamples] model.
 * Each section picks default and "more" examples by id prefix.
 */
val allExampleSections = listOf(
    ToolExampleSection(
        id = "lists",
        title = "Lists",
        groupTag = "tools_learn_group_lists",
        viewMoreTag = "tools_learn_view_more_lists",
        defaultExamples = toPrompts("lists_add_milk", "lists_chuck_bananas"),
        moreExamples = toPrompts("lists_create", "lists_show", "lists_remove", "lists_bulk"),
    ),
    ToolExampleSection(
        id = "meal_planning",
        title = "Meal planning",
        groupTag = "tools_learn_group_meal_planning",
        viewMoreTag = "tools_learn_view_more_meal_planning",
        defaultExamples = toPrompts("meal_plan_meals", "meal_plan_dinners_week", "meal_plan_shopping_list"),
        moreExamples = toPrompts("meal_plan_grocery", "meal_plan_lunches", "meal_plan_family",
            "meal_plan_5day", "meal_plan_vegetarian", "meal_plan_pantry"),
    ),
    ToolExampleSection(
        id = "notes_memory",
        title = "Notes & memory",
        groupTag = "tools_learn_group_notes_memory",
        viewMoreTag = "tools_learn_view_more_notes_memory",
        defaultExamples = toPrompts("notes_note_to_self", "memory_dark_mode"),
        moreExamples = toPrompts("notes_show", "memory_coffee", "notes_freyja", "notes_packing"),
    ),
    ToolExampleSection(
        id = "time_planning",
        title = "Time & planning",
        groupTag = "tools_learn_group_time_planning",
        viewMoreTag = "tools_learn_view_more_time_planning",
        defaultExamples = toPrompts("time_timer_10", "calendar_soccer_training"),
        moreExamples = toPrompts("calendar_dentist", "calendar_assembly", "reminder_bins",
            "date_diff_christmas", "alarm_7am", "date_birthday", "date_diff_july"),
    ),
    ToolExampleSection(
        id = "weather",
        title = "Weather",
        groupTag = "tools_learn_group_weather",
        viewMoreTag = "tools_learn_view_more_weather",
        defaultExamples = toPrompts("weather_current", "weather_5_day_forecast"),
        moreExamples = toPrompts("weather_wellington", "weather_rain_today", "weather_tomorrow",
            "weather_bundaberg", "weather_umbrella", "weather_hot_tomorrow"),
    ),
    ToolExampleSection(
        id = "people_communication",
        title = "People & communication",
        groupTag = "tools_learn_group_people_communication",
        viewMoreTag = "tools_learn_view_more_people_communication",
        defaultExamples = toPrompts("people_email_alex", "people_text_running_late"),
        moreExamples = toPrompts("people_email_school", "people_text_dad", "people_call_mum"),
    ),
    ToolExampleSection(
        id = "device_media",
        title = "Device & media",
        groupTag = "tools_learn_group_device_media",
        viewMoreTag = "tools_learn_view_more_device_media",
        defaultExamples = toPrompts("device_flashlight_on", "media_play_music"),
        moreExamples = toPrompts("device_flashlight_off", "media_next_track", "media_pause",
            "media_youtube_bluey", "media_youtube_music", "media_open_spotify", "media_workout_playlist"),
    ),
    ToolExampleSection(
        id = "maps_places",
        title = "Maps & places",
        groupTag = "tools_learn_group_maps_places",
        viewMoreTag = "tools_learn_view_more_maps_places",
        defaultExamples = toPrompts("maps_navigate_school", "maps_find_coffee"),
        moreExamples = toPrompts("maps_navigate_home", "maps_find_petrol", "maps_find_bunnings",
            "maps_navigate_pharmacy", "maps_find_parks", "maps_find_supermarket"),
    ),
    ToolExampleSection(
        id = "utilities_conversions",
        title = "Utilities & conversions",
        groupTag = "tools_learn_group_utilities_conversions",
        viewMoreTag = "tools_learn_view_more_utilities_conversions",
        defaultExamples = toPrompts("convert_cups_ml", "convert_flour_grams"),
        moreExamples = toPrompts("convert_butter", "convert_currency_aud_nzd", "convert_percentage",
            "convert_distance", "convert_currency_usd_aud"),
    ),
)

/** Look up [LearnExample]s by id and convert to [ToolExamplePrompt]s for the UI. */
private fun toPrompts(vararg ids: String): List<ToolExamplePrompt> = ids.map { id ->
    val ex = requireNotNull(learnExamplesById[id]) { "Unknown LearnExample id=$id" }
    ToolExamplePrompt(id = ex.id, label = ex.title, prompt = ex.prompt)
}

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
                        testTag = "tools_learn_${example.id}",
                        label = example.label,
                        onClick = { onOpenPrompt(example.prompt) },
                    )
                }

                if (isExpanded) {
                    section.moreExamples.forEach { example ->
                        ToolsExampleRow(
                            testTag = "tools_learn_${example.id}",
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
