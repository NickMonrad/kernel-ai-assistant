package com.kernel.ai.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A shortcut definition in the tool shortcut registry.
 *
 * @param id Stable identifier used for persistence and lookup.
 * @param label Human-readable display name.
 * @param icon Icon displayed in the drawer.
 * @param route Navigation route or deep-link.
 * @param parentToolId For sub-feature shortcuts (e.g. "clock.stopwatch" parent = "clock").
 * @param canFavourite Whether this shortcut can be added to favourites.
 * @param canRecordRecent Whether opens of this shortcut are tracked as recently used.
 * @param isSettings Whether this is the Settings entry (pinned separately in drawer).
 */
data class ShortcutDef(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val route: String,
    val parentToolId: String? = null,
    val canFavourite: Boolean = true,
    val canRecordRecent: Boolean = true,
    val isSettings: Boolean = false,
)

/**
 * Central registry of eligible tool shortcuts.
 *
 * All shortcuts have stable IDs, labels, icons, and navigation routes.
 * The registry serves as the single source of truth for drawer defaults,
 * favourite eligibility, and recently-used tracking.
 */
object ShortcutRegistry {

    /** Top-level productivity shortcuts. */
    val lists = ShortcutDef(
        id = "lists",
        label = "Lists",
        icon = Icons.Default.Checklist,
        route = ROUTE_LISTS,
    )

    val notes = ShortcutDef(
        id = "notes",
        label = "Notes",
        icon = Icons.Default.Note,
        route = ROUTE_NOTES,
    )

    val mealPlans = ShortcutDef(
        id = "meal_plans",
        label = "Meal plans",
        icon = Icons.Default.Bookmarks,
        route = ROUTE_MEAL_PLANS,
    )

    /** Time & planning shortcuts. */
    val clock = ShortcutDef(
        id = "clock",
        label = "Clock",
        icon = Icons.Default.Timer,
        route = ROUTE_SIDE_PANEL,
    )

    val importantDates = ShortcutDef(
        id = "important_dates",
        label = "Important dates",
        icon = Icons.Default.Event,
        route = ROUTE_IMPORTANT_DATES,
    )

    /** People shortcuts. */
    val peopleContacts = ShortcutDef(
        id = "people_contacts",
        label = "People & Contacts",
        icon = Icons.Default.People,
        route = ROUTE_CONTACT_ALIASES,
    )

    /** Utility shortcuts. */
    val convert = ShortcutDef(
        id = "convert",
        label = "Convert",
        icon = Icons.Default.Calculate,
        route = ROUTE_CONVERT,
    )

    /** Learn catalogue shortcut (recordable from Tools, not favourite-eligible). */
    val learn = ShortcutDef(
        id = "learn",
        label = "Learn what Jandal can do",
        icon = Icons.AutoMirrored.Filled.MenuBook,
        route = ROUTE_TOOLS_LEARN,
        canFavourite = false,
    )

    /** Personalisation shortcuts (recordable from Tools, not favourite-eligible). */
    val userProfile = ShortcutDef(
        id = "user_profile",
        label = "User Profile",
        icon = Icons.Default.Person,
        route = ROUTE_USER_PROFILE,
        canFavourite = false,
    )

    val memory = ShortcutDef(
        id = "memory",
        label = "Memory",
        icon = Icons.Default.Bookmarks,
        route = ROUTE_MEMORY,
        canFavourite = false,
    )

    val voice = ShortcutDef(
        id = "voice",
        label = "Voice",
        icon = Icons.Default.Tune,
        route = ROUTE_VOICE,
        canFavourite = false,
    )

    val chatPreferences = ShortcutDef(
        id = "chat_preferences",
        label = "Chat Preferences",
        icon = Icons.Default.Forum,
        route = ROUTE_CHAT_PREFERENCES,
        canFavourite = false,
    )

    /** App setup shortcuts (recordable from Tools, not favourite-eligible). */
    val models = ShortcutDef(
        id = "models",
        label = "Models",
        icon = Icons.Default.SmartToy,
        route = buildModelManagementRoute(),
        canFavourite = false,
    )

    val permissions = ShortcutDef(
        id = "permissions",
        label = "Permissions",
        icon = Icons.Default.Security,
        route = ROUTE_APP_PERMISSIONS,
        canFavourite = false,
    )

    val about = ShortcutDef(
        id = "about",
        label = "About",
        icon = Icons.Default.Info,
        route = ROUTE_ABOUT,
        canFavourite = false,
    )

    /** Settings (always pinned, not favourite-able, not recent-able). */
    val settings = ShortcutDef(
        id = "settings",
        label = "Settings",
        icon = Icons.Default.Settings,
        route = ROUTE_SETTINGS,
        canFavourite = false,
        canRecordRecent = false,
        isSettings = true,
    )

    /** Clock sub-feature shortcuts (eligible for favourites/recents). */
    val clockStopwatch = ShortcutDef(
        id = "clock.stopwatch",
        label = "Stopwatch",
        icon = Icons.Default.Timer,
        route = buildSidePanelTabRoute("stopwatch"),
        parentToolId = "clock",
    )

    val clockTimer = ShortcutDef(
        id = "clock.timer",
        label = "Timer",
        icon = Icons.Default.Timer,
        route = buildSidePanelTabRoute("timer"),
        parentToolId = "clock",
    )

    val clockAlarms = ShortcutDef(
        id = "clock.alarms",
        label = "Alarms",
        icon = Icons.Default.Timer,
        route = buildSidePanelTabRoute("alarms"),
        parentToolId = "clock",
    )

    val clockWorldClock = ShortcutDef(
        id = "clock.world_clock",
        label = "World Clock",
        icon = Icons.Default.Timer,
        route = buildSidePanelTabRoute("world_clock"),
        parentToolId = "clock",
    )

    /** Convert sub-feature shortcuts (eligible for favourites/recents). */
    val convertCurrency = ShortcutDef(
        id = "convert.currency",
        label = "Currency",
        icon = Icons.Default.Calculate,
        route = buildConvertTabRoute("currency"),
        parentToolId = "convert",
    )

    val convertUnit = ShortcutDef(
        id = "convert.unit",
        label = "Unit conversion",
        icon = Icons.Default.Calculate,
        route = buildConvertTabRoute("unit"),
        parentToolId = "convert",
    )

    val convertCooking = ShortcutDef(
        id = "convert.cooking",
        label = "Cooking conversion",
        icon = Icons.Default.Calculate,
        route = buildConvertTabRoute("cooking"),
        parentToolId = "convert",
    )

    val allById: Map<String, ShortcutDef> = listOf(
        lists, notes, mealPlans,
        clock, importantDates, peopleContacts, convert,
        learn, userProfile, memory, voice, chatPreferences,
        models, permissions, about,
        settings,
        clockStopwatch, clockTimer, clockAlarms, clockWorldClock,
        convertCurrency, convertUnit, convertCooking,
    ).associateBy { it.id }

    private val allByRoute: Map<String, ShortcutDef> = allById.values.associateBy { it.route }

    /** All top-level (non-sub-feature) shortcuts that can appear in the drawer. */
    val allTopLevel: List<ShortcutDef> = listOf(
        lists, notes, mealPlans,
        clock, importantDates, peopleContacts, convert,
    )

    /** Default drawer shortcuts when no favourites or recents are set. */
    val drawerDefaults: List<ShortcutDef> = allTopLevel

    /** All eligible (canFavourite) shortcut IDs. */
    val allFavouriteEligibleIds: Set<String> = allById.values
        .filter { it.canFavourite }
        .map { it.id }
        .toSet()

    /** Resolve a shortcut by ID, returning null for unknown IDs. */
    fun byId(id: String): ShortcutDef? = allById[id]

    /** Resolve a shortcut by navigation route, including supported tabbed route variants. */
    fun byRoute(route: String): ShortcutDef? {
        val normalized = route.trim()
        if (normalized.isBlank()) return null

        val baseRoute = normalized.substringBefore("?")
        return when (baseRoute) {
            ROUTE_SIDE_PANEL -> when (normalized.queryParam("tab").orEmpty()) {
                "" -> clock
                "stopwatch" -> clockStopwatch
                "timer" -> clockTimer
                "alarms" -> clockAlarms
                "world_clock" -> clockWorldClock
                else -> null
            }
            ROUTE_CONVERT -> when (normalized.queryParam("tab").orEmpty()) {
                "" -> convert
                "currency" -> convertCurrency
                "unit" -> convertUnit
                "cooking" -> convertCooking
                else -> null
            }
            buildModelManagementRoute().substringBefore("?") -> models
            else -> allByRoute[normalized]
        }
    }

    private fun String.queryParam(name: String): String? {
        val query = substringAfter("?", missingDelimiterValue = "")
        if (query.isBlank()) return null
        return query.split("&")
            .mapNotNull { pair ->
                val key = pair.substringBefore("=")
                val value = pair.substringAfter("=", missingDelimiterValue = "")
                value.takeIf { key == name }
            }
            .firstOrNull()
    }

    /** Check whether an ID is a known shortcut. */
    fun isValidId(id: String): Boolean = id in allById
}
