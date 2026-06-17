package com.kernel.ai.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


/**
 * Search metadata for a Tools destination row.
 */
private data class ToolSearchEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val keywords: List<String> = emptyList(),
)

/**
 * Search metadata for a Learn example prompt.
 */
private data class ExampleSearchEntry(
    val sectionTitle: String,
    val id: String,
    val label: String,
    val prompt: String,
)

/** All Tools destinations as searchable entries. */
private val allToolSearchEntries = listOf(
    ToolSearchEntry("learn", "Learn what Jandal can do", "Example prompts for actions, planning, weather, maps, media, and more", "Learn",
        keywords = listOf("examples", "prompts", "tutorial", "guide", "help", "ideas")),
    ToolSearchEntry("lists", "Lists", "Shopping, tasks, and reusable lists", "Productivity",
        keywords = listOf("shopping", "tasks", "grocery", "checklist", "todo")),
    ToolSearchEntry("notes", "Notes", "Quick notes and saved thoughts", "Productivity",
        keywords = listOf("memo", "reminder", "thoughts", "write")),
    ToolSearchEntry("meal_plans", "Meal plans", "Plan meals and generate shopping ideas", "Productivity",
        keywords = listOf("meal planning", "dinner", "recipes", "grocery", "food")),
    ToolSearchEntry("clock", "Clock", "Alarms, timers, stopwatch, and world clock", "Time & planning",
        keywords = listOf("timer", "alarm", "stopwatch", "time")),
    ToolSearchEntry("important_dates", "Important dates", "Birthdays, anniversaries, and recurring dates", "Time & planning",
        keywords = listOf("birthday", "anniversary", "recurring", "reminder")),
    ToolSearchEntry("people_contacts", "People & Contacts", "Contact aliases and people Jandal can recognise", "People",
        keywords = listOf("contacts", "people", "aliases", "address book")),
    ToolSearchEntry("convert", "Convert", "Currency, units, and cooking conversions", "Utilities",
        keywords = listOf("unit", "currency", "measurement", "cooking", "calculate")),
    ToolSearchEntry("user_profile", "User Profile", "Tell Jandal about yourself", "Personalisation",
        keywords = listOf("profile", "name", "about me", "preferences")),
    ToolSearchEntry("memory", "Memory", "Manage stored memories", "Personalisation",
        keywords = listOf("remember", "memories", "storage")),
    ToolSearchEntry("voice", "Voice", "Speech and spoken response settings", "Personalisation",
        keywords = listOf("speech", "tts", "stt", "spoken", "audio")),
    ToolSearchEntry("chat_preferences", "Chat Preferences", "Archive, themes, wallpaper, and copy options", "Personalisation",
        keywords = listOf("archive", "theme", "wallpaper", "copy", "chat")),
    ToolSearchEntry("settings", "Settings", "App preferences and configuration", "App setup",
        keywords = listOf("preferences", "configuration", "options")),
    ToolSearchEntry("models", "Models", "Downloads, availability, and inference preferences", "App setup",
        keywords = listOf("ai", "llm", "download", "inference", "gemma")),
    ToolSearchEntry("permissions", "Permissions", "Review Android permissions used by Jandal", "App setup",
        keywords = listOf("android", "privacy", "security", "access")),
    ToolSearchEntry("about", "About", "Build info and debug tools", "App setup",
        keywords = listOf("version", "build", "debug", "info", "licence")),
)

/** All Learn example prompts as flat searchable entries. */
private val allExampleSearchEntries: List<ExampleSearchEntry> by lazy {
    allExampleSections.flatMap { section ->
        (section.defaultExamples + section.moreExamples).map { prompt ->
            ExampleSearchEntry(
                sectionTitle = section.title,
                id = prompt.id.removePrefix("tools_learn_"),
                label = prompt.label,
                prompt = prompt.prompt,
            )
        }
    }
}

/** Check whether any field in this entry matches [query]. */
private fun ToolSearchEntry.matchesQuery(query: String): Boolean {
    val q = query.lowercase().trim()
    return title.lowercase().contains(q) ||
        subtitle.lowercase().contains(q) ||
        category.lowercase().contains(q) ||
        keywords.any { it.lowercase().contains(q) }
}

/** Check whether this example entry matches [query]. */
private fun ExampleSearchEntry.matchesQuery(query: String): Boolean {
    val q = query.lowercase().trim()
    return label.lowercase().contains(q) ||
        prompt.lowercase().contains(q) ||
        sectionTitle.lowercase().contains(q)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToRoute: (route: String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onOpenPrompt: (prompt: String) -> Unit = {},
    favouriteIds: Set<String> = emptySet(),
    onToggleFavourite: (String) -> Unit = {},
) {
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    var learnSectionExpanded by remember {
        val prefs = context.getSharedPreferences("tools_hub", Context.MODE_PRIVATE)
        mutableStateOf(prefs.getBoolean("tools_learn_expanded", true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.testTag("tools_menu_button")) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("top_bar_settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            // ── Search field ────────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tools…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.testTag("tools_search_clear")) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("tools_search_field"),
            )

            if (searchQuery.isBlank()) {
                // ── Full grouped layout ──────────────────────────────────────
                // Compact, collapsible Learn entry
                if (learnSectionExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToRoute(ROUTE_TOOLS_LEARN) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("tools_row_learn"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Learn what Jandal can do",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "Example prompts to get started",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                learnSectionExpanded = false
                                context.getSharedPreferences("tools_hub", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("tools_learn_expanded", false)
                                    .apply()
                            },
                            modifier = Modifier.testTag("tools_learn_collapse"),
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Collapse")
                        }
                    }
                    HorizontalDivider()
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                learnSectionExpanded = true
                                context.getSharedPreferences("tools_hub", Context.MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("tools_learn_expanded", true)
                                    .apply()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("tools_learn_collapsed"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Getting started",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Show",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }

                Text(
                    text = "Productivity",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tools_group_productivity"),
                )
                val (listsCanFav, listsIsFav, listsOnFav) = favouriteParams("lists", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_lists",
                    icon = Icons.Default.Checklist,
                    title = "Lists",
                    subtitle = "Shopping, tasks, and reusable lists",
                    onClick = { onNavigateToRoute(ROUTE_LISTS) },
                    canFavourite = listsCanFav,
                    isFavourite = listsIsFav,
                    onToggleFavourite = listsOnFav,
                )
                HorizontalDivider()
                val (notesCanFav, notesIsFav, notesOnFav) = favouriteParams("notes", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_notes",
                    icon = Icons.Default.Note,
                    title = "Notes",
                    subtitle = "Quick notes and saved thoughts",
                    onClick = { onNavigateToRoute(ROUTE_NOTES) },
                    canFavourite = notesCanFav,
                    isFavourite = notesIsFav,
                    onToggleFavourite = notesOnFav,
                )
                HorizontalDivider()
                val (mealCanFav, mealIsFav, mealOnFav) = favouriteParams("meal_plans", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_meal_plans",
                    icon = Icons.Default.Bookmarks,
                    title = "Meal plans",
                    subtitle = "Plan meals and generate shopping ideas",
                    onClick = { onNavigateToRoute(ROUTE_MEAL_PLANS) },
                    canFavourite = mealCanFav,
                    isFavourite = mealIsFav,
                    onToggleFavourite = mealOnFav,
                )
                HorizontalDivider()

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Time & planning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tools_group_time_planning"),
                )
                val (clockCanFav, clockIsFav, clockOnFav) = favouriteParams("clock", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_clock",
                    icon = Icons.Default.Timer,
                    title = "Clock",
                    subtitle = "Alarms, timers, stopwatch, and world clock",
                    onClick = { onNavigateToRoute(ROUTE_SIDE_PANEL) },
                    canFavourite = clockCanFav,
                    isFavourite = clockIsFav,
                    onToggleFavourite = clockOnFav,
                )
                HorizontalDivider()

                // Clock sub-feature shortcuts (indented, with favourite toggles)
                val stopwatch = ShortcutRegistry.clockStopwatch
                val timer = ShortcutRegistry.clockTimer
                val alarms = ShortcutRegistry.clockAlarms
                ClockSubFeatureRow(
                    testTag = "tools_row_clock_stopwatch",
                    title = stopwatch.label,
                    subtitle = "Clock",
                    route = stopwatch.route,
                    canFavourite = stopwatch.canFavourite,
                    isFavourite = stopwatch.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(stopwatch.id) },
                    onNavigate = onNavigateToRoute,
                )
                ClockSubFeatureRow(
                    testTag = "tools_row_clock_timer",
                    title = timer.label,
                    subtitle = "Clock",
                    route = timer.route,
                    canFavourite = timer.canFavourite,
                    isFavourite = timer.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(timer.id) },
                    onNavigate = onNavigateToRoute,
                )
                ClockSubFeatureRow(
                    testTag = "tools_row_clock_alarms",
                    title = alarms.label,
                    subtitle = "Clock",
                    route = alarms.route,
                    canFavourite = alarms.canFavourite,
                    isFavourite = alarms.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(alarms.id) },
                    onNavigate = onNavigateToRoute,
                )
                val worldClock = ShortcutRegistry.clockWorldClock
                ClockSubFeatureRow(
                    testTag = "tools_row_clock_world_clock",
                    title = worldClock.label,
                    subtitle = "Clock",
                    route = worldClock.route,
                    canFavourite = worldClock.canFavourite,
                    isFavourite = worldClock.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(worldClock.id) },
                    onNavigate = onNavigateToRoute,
                )
                HorizontalDivider()
                val (datesCanFav, datesIsFav, datesOnFav) = favouriteParams("important_dates", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_important_dates",
                    icon = Icons.Default.Event,
                    title = "Important dates",
                    subtitle = "Birthdays, anniversaries, and recurring dates",
                    onClick = { onNavigateToRoute(ROUTE_IMPORTANT_DATES) },
                    canFavourite = datesCanFav,
                    isFavourite = datesIsFav,
                    onToggleFavourite = datesOnFav,
                )
                HorizontalDivider()

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "People",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tools_group_people"),
                )
                val (peopleCanFav, peopleIsFav, peopleOnFav) = favouriteParams("people_contacts", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_people_contacts",
                    icon = Icons.Default.People,
                    title = "People & Contacts",
                    subtitle = "Contact aliases and people Jandal can recognise",
                    onClick = { onNavigateToRoute(ROUTE_CONTACT_ALIASES) },
                    canFavourite = peopleCanFav,
                    isFavourite = peopleIsFav,
                    onToggleFavourite = peopleOnFav,
                )
                HorizontalDivider()

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Utilities",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tools_group_utilities"),
                )
                val (convertCanFav, convertIsFav, convertOnFav) = favouriteParams("convert", favouriteIds, onToggleFavourite)
                ToolsListItem(
                    testTag = "tools_row_convert",
                    icon = Icons.Default.Calculate,
                    title = "Convert",
                    subtitle = "Currency, units, and cooking conversions",
                    onClick = { onNavigateToRoute(ROUTE_CONVERT) },
                    canFavourite = convertCanFav,
                    isFavourite = convertIsFav,
                    onToggleFavourite = convertOnFav,
                )

                // Convert sub-feature shortcuts (indented, with favourite toggles)
                val convertCurrency = ShortcutRegistry.convertCurrency
                val convertUnit = ShortcutRegistry.convertUnit
                val convertCooking = ShortcutRegistry.convertCooking
                ClockSubFeatureRow(
                    testTag = "tools_row_convert_currency",
                    title = convertCurrency.label,
                    subtitle = "Convert",
                    route = convertCurrency.route,
                    canFavourite = convertCurrency.canFavourite,
                    isFavourite = convertCurrency.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(convertCurrency.id) },
                    onNavigate = onNavigateToRoute,
                )
                ClockSubFeatureRow(
                    testTag = "tools_row_convert_unit",
                    title = convertUnit.label,
                    subtitle = "Convert",
                    route = convertUnit.route,
                    canFavourite = convertUnit.canFavourite,
                    isFavourite = convertUnit.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(convertUnit.id) },
                    onNavigate = onNavigateToRoute,
                )
                ClockSubFeatureRow(
                    testTag = "tools_row_convert_cooking",
                    title = convertCooking.label,
                    subtitle = "Convert",
                    route = convertCooking.route,
                    canFavourite = convertCooking.canFavourite,
                    isFavourite = convertCooking.id in favouriteIds,
                    onToggleFavourite = { onToggleFavourite(convertCooking.id) },
                    onNavigate = onNavigateToRoute,
                )
                HorizontalDivider()

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Personalisation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
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

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "App setup",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
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
            } else {
                // ── Search results ───────────────────────────────────────────
                val matchedDestinations = allToolSearchEntries.filter { it.matchesQuery(searchQuery) }
                val matchedExamples = allExampleSearchEntries.filter { it.matchesQuery(searchQuery) }

                if (matchedDestinations.isEmpty() && matchedExamples.isEmpty()) {
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(32.dp)
                            .testTag("tools_search_no_results"),
                    )
                } else {
                    if (matchedDestinations.isNotEmpty()) {
                        Text(
                            text = "Tools",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("tools_search_results_header"),
                        )
                        matchedDestinations.forEach { entry ->
                            ToolsListItem(
                                testTag = "tools_search_result_${entry.id}",
                                icon = iconForEntryId(entry.id),
                                title = entry.title,
                                subtitle = entry.subtitle,
                                onClick = {
                                    val route = routeForEntryId(entry.id)
                                    if (route != null) {
                                        onNavigateToRoute(route)
                                    } else if (entry.id == "learn") {
                                        onNavigateToRoute(ROUTE_TOOLS_LEARN)
                                    }
                                },
                            )
                            if (entry != matchedDestinations.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                    if (matchedExamples.isNotEmpty()) {
                        if (matchedDestinations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                        }
                        Text(
                            text = "Example prompts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("tools_search_examples_header"),
                        )
                        matchedExamples.forEach { entry ->
                            ToolsListItem(
                                testTag = "tools_search_example_${entry.id}",
                                icon = iconForEntryId(entry.id.substringBefore("_")),
                                title = entry.label,
                                subtitle = entry.sectionTitle,
                                onClick = { onOpenPrompt(entry.prompt) },
                            )
                            if (entry != matchedExamples.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Map a Tools entry ID to an icon for search results. */
private fun iconForEntryId(id: String): ImageVector = when (id) {
    "learn" -> Icons.AutoMirrored.Filled.MenuBook
    "lists" -> Icons.Default.Checklist
    "notes" -> Icons.Default.Note
    "meal_plans" -> Icons.Default.Bookmarks
    "clock" -> Icons.Default.Timer
    "important_dates" -> Icons.Default.Event
    "people_contacts" -> Icons.Default.People
    "convert" -> Icons.Default.Calculate
    "user_profile" -> Icons.Default.Person
    "memory" -> Icons.Default.Bookmarks
    "voice" -> Icons.Default.Tune
    "chat_preferences" -> Icons.Default.Forum
    "settings" -> Icons.Default.Settings
    "models" -> Icons.Default.SmartToy
    "permissions" -> Icons.Default.Security
    "about" -> Icons.Default.Info
    else -> Icons.AutoMirrored.Filled.MenuBook
}

/** Map a Tools entry ID to its navigation route, or null for special destinations. */
private fun routeForEntryId(id: String): String? = when (id) {
    "lists" -> ROUTE_LISTS
    "notes" -> ROUTE_NOTES
    "meal_plans" -> ROUTE_MEAL_PLANS
    "clock" -> ROUTE_SIDE_PANEL
    "important_dates" -> ROUTE_IMPORTANT_DATES
    "people_contacts" -> ROUTE_CONTACT_ALIASES
    "convert" -> ROUTE_CONVERT
    "user_profile" -> ROUTE_USER_PROFILE
    "memory" -> ROUTE_MEMORY
    "voice" -> ROUTE_VOICE
    "chat_preferences" -> ROUTE_CHAT_PREFERENCES
    "settings" -> ROUTE_SETTINGS
    "models" -> buildModelManagementRoute()
    "permissions" -> ROUTE_APP_PERMISSIONS
    "about" -> ROUTE_ABOUT
    else -> null
}

/** Build favourite params for a Tools row. */
private fun favouriteParams(
    id: String,
    favouriteIds: Set<String>,
    onToggleFavourite: (String) -> Unit,
): Triple<Boolean, Boolean, () -> Unit> = Triple(
    id in ShortcutRegistry.allFavouriteEligibleIds && id != "settings",
    id in favouriteIds,
    { onToggleFavourite(id) },
)
@Composable
private fun ToolsListItem(
    testTag: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    canFavourite: Boolean = false,
    isFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (canFavourite) {
            {
                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier.testTag("${testTag}_favourite"),
                ) {
                    Icon(
                        imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    )
                }
            }
        } else {
            { Icon(Icons.Default.ChevronRight, contentDescription = null) }
        },
    )
}

/**
 * An indented sub-feature row for clock shortcuts (Stopwatch, Timer, Alarms).
 *
 * Shows a compact row with a favourite star toggle. Clicking the row navigates
 * to the parent Clock screen with the corresponding tab selected via query param.
 */
@Composable
private fun ClockSubFeatureRow(
    testTag: String,
    title: String,
    subtitle: String,
    route: String,
    canFavourite: Boolean,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(route) }
            .padding(start = 32.dp)
            .testTag(testTag),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (canFavourite) {
            {
                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier.testTag("${testTag}_favourite"),
                ) {
                    Icon(
                        imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    )
                }
            }
        } else {
            null
        },
    )
}