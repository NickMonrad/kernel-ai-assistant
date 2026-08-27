package com.kernel.ai.navigation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import com.kernel.ai.core.memory.entity.FavouriteShortcutEntity
import com.kernel.ai.core.memory.entity.RecentShortcutEntity
import com.kernel.ai.core.memory.shortcut.FavouriteShortcutRepository
import com.kernel.ai.core.memory.shortcut.RecentShortcutTracker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import com.kernel.ai.feature.settings.ClockSettingsScreen
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kernel.ai.feature.chat.ActionsScreen
import com.kernel.ai.feature.chat.ChatScreen
import com.kernel.ai.feature.chat.ConversationListScreen
import com.kernel.ai.feature.convert.ConvertScreen
import com.kernel.ai.feature.convert.mapQueryParamToConvertTab
import com.kernel.ai.feature.settings.AppPermissionsScreen
import com.kernel.ai.feature.settings.AboutScreen
import com.kernel.ai.feature.settings.ContactAliasesScreen
import com.kernel.ai.feature.settings.ImportantDatesScreen
import com.kernel.ai.feature.settings.ListItemsScreen
import com.kernel.ai.feature.settings.ChatPreferencesScreen
import com.kernel.ai.feature.settings.ListsScreen
import com.kernel.ai.feature.settings.MealPlansScreen
import com.kernel.ai.feature.settings.MemoryScreen
import com.kernel.ai.feature.settings.ModelManagementScreen
import com.kernel.ai.feature.settings.ModelSettingsScreen
import com.kernel.ai.feature.settings.ScheduledAlarmsScreen
import com.kernel.ai.feature.settings.SettingsScreen
import com.kernel.ai.feature.settings.SidePanelScreen
import com.kernel.ai.feature.settings.UserProfileScreen
import com.kernel.ai.feature.settings.NotesScreen
import com.kernel.ai.feature.settings.NoteDetailScreen
import com.kernel.ai.feature.settings.VoiceScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch
private const val ROUTE_LIST = "conversation_list"
private const val ROUTE_ACTIONS = "actions"
private const val ROUTE_ACTIONS_OPEN = "actions?openSheet=true"
private const val ROUTE_ACTIONS_VOICE = "actions?startVoice=true"
private const val ROUTE_CHAT = "chat"
internal const val ROUTE_SETTINGS = "settings"
internal const val ROUTE_USER_PROFILE = "settings/user_profile"
internal const val ROUTE_MEMORY = "settings/memory"
internal const val ROUTE_IMPORTANT_DATES = "settings/important_dates"
internal const val ROUTE_VOICE = "settings/voice"
private const val ROUTE_MODEL_SETTINGS = "settings/model_settings"
private const val ROUTE_MODEL_MANAGEMENT = "settings/model_management?scrollTo={scrollTo}"
private const val ARG_SCROLL_TO = "scrollTo"
internal const val ROUTE_ABOUT = "settings/about"
internal const val ROUTE_APP_PERMISSIONS = "settings/app_permissions"
internal const val ROUTE_CHAT_PREFERENCES = "settings/chat_preferences"
internal const val ROUTE_CONTACT_ALIASES = "settings/contact_aliases"
private const val ROUTE_SCHEDULED_ALARMS = "settings/scheduled_alarms"
internal const val ROUTE_SIDE_PANEL = "settings/side_panel"
internal const val ROUTE_CLOCK_SETTINGS = "settings/clock_settings"
internal const val ROUTE_MEAL_PLANS = "meal_plans"
internal const val ROUTE_LISTS = "lists"
private const val ROUTE_LIST_ITEMS = "lists/{listId}"
private const val ROUTE_TOOLS = "tools"
internal const val ROUTE_TOOLS_LEARN = "tools/learn"
internal const val ROUTE_CONVERT = "convert"
internal const val ROUTE_NOTES = "settings/notes"
private const val ROUTE_NOTE_DETAIL = "settings/notes/{noteId}"
private const val ARG_NOTE_ID = "noteId"
private const val ARG_LIST_ID = "listId"
private const val ARG_CONVERSATION_ID = "conversationId"
private const val ARG_INITIAL_QUERY = "initialQuery"
private const val ARG_MINIMAL_CONTEXT = "minimalContext"
private const val ARG_SPEAK_RESPONSE = "speakResponse"
private const val ARG_START_VOICE = "startVoice"
private val LISTS_WIDGET_DEEP_LINK = Regex("^lists/\\d+$")
private const val ARG_WIDGET_QUERY = "widgetQuery"
private const val ARG_WIDGET_VOICE = "widgetVoice"
private const val ARG_DRAFT_QUERY = "draftQuery"
private const val STATE_OPEN_SHEET_CONSUMED = "openSheetConsumed"
private const val STATE_START_VOICE_CONSUMED = "startVoiceConsumed"
private const val STATE_WIDGET_QUERY_CONSUMED = "widgetQueryConsumed"
private const val NEW_MEAL_PLAN_INITIAL_QUERY = "plan meals"

private val BOTTOM_NAV_ROUTES = setOf(ROUTE_LIST, ROUTE_ACTIONS, ROUTE_TOOLS)

internal fun buildChatRoute(
    initialQuery: String? = null,
    minimalContext: Boolean = false,
    speakResponse: Boolean = false,
): String {
    val encodedQuery = initialQuery?.trim()?.takeIf { it.isNotEmpty() }?.let(::encodeRouteQueryValue)
    val params = buildList {
        encodedQuery?.let { add("$ARG_INITIAL_QUERY=$it") }
        if (minimalContext) add("$ARG_MINIMAL_CONTEXT=true")
        if (speakResponse) add("$ARG_SPEAK_RESPONSE=true")
    }
    return if (params.isEmpty()) ROUTE_CHAT else "$ROUTE_CHAT?${params.joinToString("&")}"
}

internal fun buildNewMealPlanChatRoute(): String =
    buildChatRoute(
        initialQuery = NEW_MEAL_PLAN_INITIAL_QUERY,
        minimalContext = true,
    )

internal fun buildModelManagementRoute(scrollTo: Boolean = false): String =
    "settings/model_management?scrollTo=$scrollTo"

internal fun buildSidePanelTabRoute(tab: String): String =
    "$ROUTE_SIDE_PANEL?tab=$tab"

internal fun buildConvertTabRoute(tab: String): String =
    "$ROUTE_CONVERT?tab=$tab"

internal fun buildActionsDraftRoute(draftQuery: String): String =
    "$ROUTE_ACTIONS?openSheet=true&$ARG_DRAFT_QUERY=${encodeRouteQueryValue(draftQuery)}"

internal fun encodeRouteQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

/**
 * Determine whether a drawer shortcut should render as selected given the current
 * navigation destination.
 *
 * When the shortcut's route includes a `tab=` query parameter (sub-feature routes
 * like `convert?tab=currency` or `settings/side_panel?tab=stopwatch`), both the
 * base route and the tab must match. Top-level shortcuts with no tab parameter
 * use base-route matching only.
 */
internal fun isDrawerShortcutSelected(
    currentBaseRoute: String?,
    currentTab: String?,
    itemRoute: String,
): Boolean {
    val itemBase = itemRoute.substringBefore('?')
    val itemTab = itemRoute
        .substringAfter("tab=", "")
        .takeIf { it.isNotBlank() }
    return when {
        itemTab != null -> currentBaseRoute == itemBase && currentTab == itemTab
        else -> currentBaseRoute == itemBase && currentTab == null
    }
}

/** True when a shortcut click should perform navigation instead of being treated as a no-op. */
internal fun shouldNavigateForShortcut(
    currentBaseRoute: String?,
    currentTab: String?,
    targetRoute: String,
): Boolean {
    val targetBase = targetRoute.substringBefore('?')
    val targetQuery = targetRoute.substringAfter("?", missingDelimiterValue = "")
    val targetTab = targetQuery
        .split("&")
        .mapNotNull { pair ->
            val key = pair.substringBefore("=")
            val value = pair.substringAfter("=", missingDelimiterValue = "")
            value.takeIf { key == "tab" && it.isNotBlank() }
        }
        .firstOrNull()

    return when {
        currentBaseRoute != targetBase -> true
        targetQuery.isNotBlank() && targetTab == null -> true
        else -> currentTab != targetTab
    }
}

private fun NavHostController.navigateToPrimaryRoute(route: String) {
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentBaseRoute = currentRoute?.substringBefore('?')
    if (currentBaseRoute == route) return

    // When the current route has transient query parameters
    // (e.g. actions?openSheet=true&draftQuery=X), navigating to another
    // primary route should not save or restore state. The parameterised
    // route may be nested under the wrong tab's back stack and restoring
    // it would bring back stale draft or sheet state.
    val hasTransientParams = currentRoute?.contains('?') == true
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = !hasTransientParams
        }
        launchSingleTop = true
        restoreState = !hasTransientParams
    }
}

private fun NavHostController.navigateToToolsDestination(route: String): Boolean {
    val entry = currentBackStackEntry
    val currentRoute = entry?.destination?.route
    val currentBase = currentRoute?.substringBefore('?')
    val currentTab = entry?.arguments?.getString("tab")?.takeIf { it.isNotBlank() }

    if (!shouldNavigateForShortcut(currentBase, currentTab, route)) return false

    navigate(route) {
        launchSingleTop = true
    }
    return true
}

/**
 * Navigate to a drawer/menu destination safely.
 *
 * Unlike bottom-nav routes, drawer destinations:
 * - Should NOT use [restoreState] — no stale transient state to restore.
 * - Should NOT use [saveState] — drawer screens are not tabs.
 * - Always pop to the Chats list to keep the back stack shallow and predictable.
 *
 * Handles query-parameterised routes (e.g. "settings/side_panel?tab=stopwatch")
 * by comparing the full route when parameters are present — allows tab-switching
 * within the same base destination.
 */
private fun NavHostController.navigateToDrawerDestination(route: String): Boolean {
    val entry = currentBackStackEntry
    val currentRoute = entry?.destination?.route
    val currentBase = currentRoute?.substringBefore('?')
    val currentTab = entry?.arguments?.getString("tab")?.takeIf { it.isNotBlank() }

    if (!shouldNavigateForShortcut(currentBase, currentTab, route)) return false

    navigate(route) {
        popUpTo(ROUTE_LIST) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
    return true
}

/**
 * Pop the back stack, or navigate to the Chats list if the stack is empty.
 *
 * Prevents the app from exiting unexpectedly when the back stack has been
 * exhausted (e.g. Settings opened from the drawer is the only entry).
 */
private fun NavHostController.popBackOrNavigateHome() {
    if (!popBackStack()) {
        navigate(ROUTE_LIST) {
            popUpTo(graph.findStartDestination().id) {
                inclusive = false
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }
}

@Composable
internal fun PrimaryBottomBar(
    currentBaseRoute: String?,
    onNavigateToRoute: (String) -> Unit,
) {
    val navItemColors = NavigationBarItemDefaults.colors(
        selectedTextColor = MaterialTheme.colorScheme.primary,
    )
    NavigationBar {
        NavigationBarItem(
            selected = currentBaseRoute == ROUTE_LIST,
            onClick = { onNavigateToRoute(ROUTE_LIST) },
            icon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
            label = { Text("Chats") },
            modifier = Modifier.testTag("bottom_nav_chats"),
            colors = navItemColors,
        )
        NavigationBarItem(
            selected = currentBaseRoute == ROUTE_ACTIONS,
            onClick = { onNavigateToRoute(ROUTE_ACTIONS) },
            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
            label = { Text("Actions") },
            modifier = Modifier.testTag("bottom_nav_actions"),
            colors = navItemColors,
        )
        NavigationBarItem(
            selected = currentBaseRoute == ROUTE_TOOLS,
            onClick = { onNavigateToRoute(ROUTE_TOOLS) },
            icon = { Icon(Icons.Default.Build, contentDescription = null) },
            label = { Text("Tools") },
            modifier = Modifier.testTag("bottom_nav_tools"),
            colors = navItemColors,
        )
    }
}
@Composable
fun KernelNavHost(
    initialChatQuery: String? = null,
    initialQuickActionQuery: String? = null,
    initialQuickActionIsVoice: Boolean = false,
    /** Monotonic counter incremented by MainActivity on every delivery — ensures the
     *  [LaunchedEffect] re-fires even when the query text is identical to the prior one. */
    quickActionSerial: Int = 0,
    initialSlotReply: String? = null,
    favouriteShortcutRepository: FavouriteShortcutRepository? = null,
    recentShortcutTracker: RecentShortcutTracker? = null,
    initialNavigationRoute: String? = null,
    /** Monotonic counter incremented by MainActivity on every delivery — ensures the
     *  [LaunchedEffect] re-fires even when the route is identical to the prior one. */
    navigationRouteSerial: Int = 0,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentBaseRoute = currentRoute?.substringBefore('?')
    val currentTab = navBackStackEntry?.arguments?.getString("tab")?.takeIf { it.isNotBlank() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // ADB test harness: navigate to chat from any screen when chat_input extra is delivered
    LaunchedEffect(initialChatQuery) {
        if (!initialChatQuery.isNullOrBlank()) {
            navController.navigate(buildChatRoute(initialQuery = initialChatQuery)) {
                popUpTo(ROUTE_LIST)
            }
        }
    }

    // Widget/ADB: navigate to Actions tab with the query baked into the route URL so it
    // lands stably in backStackEntry.arguments (not a mutableStateOf that can null-out
    // before ActionsScreen's LaunchedEffect fires).
    LaunchedEffect(initialQuickActionQuery, initialQuickActionIsVoice, quickActionSerial) {
        if (!initialQuickActionQuery.isNullOrBlank()) {
            val encoded = encodeRouteQueryValue(initialQuickActionQuery)
            navController.navigate(
                "$ROUTE_ACTIONS?$ARG_WIDGET_QUERY=$encoded&$ARG_WIDGET_VOICE=$initialQuickActionIsVoice"
            ) {
                popUpTo(ROUTE_LIST)
            }
        }
    }
    // Launcher shortcut / Lists widget: deep-link to a top-level list route
    // ("lists" overview, or "lists/{listId}" detail). Reuses the same external-entry
    // seam as the ADB chat/quick-action hooks — no new routing concept.
    LaunchedEffect(initialNavigationRoute, navigationRouteSerial) {
        val route = initialNavigationRoute
        // Only accept the Lists deep-link seam; external/garbage routes must never reach
        // navController.navigate (which would throw on an unknown route).
        if (route != null && (route == ROUTE_LISTS || route.matches(LISTS_WIDGET_DEEP_LINK))) {
            navController.navigate(route) {
                popUpTo(ROUTE_LIST)
                launchSingleTop = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only allow swipe-to-open on main bottom-nav screens
        gesturesEnabled = currentBaseRoute in BOTTOM_NAV_ROUTES,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    navController = navController,
                    drawerState = drawerState,
                    currentBaseRoute = currentBaseRoute,
                    currentTab = currentTab,
                    favouriteShortcutRepository = favouriteShortcutRepository,
                    recentShortcutTracker = recentShortcutTracker,
                )
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                if (currentBaseRoute in BOTTOM_NAV_ROUTES) {
                    PrimaryBottomBar(
                        currentBaseRoute = currentBaseRoute,
                        onNavigateToRoute = { route ->
                            navController.navigateToPrimaryRoute(route)
                        },
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_LIST,
            ) {
                composable(ROUTE_LIST) {
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ConversationListScreen(
                            onOpenConversation = { id ->
                                navController.navigate("$ROUTE_CHAT/$id")
                            },
                            onNewConversation = {
                                navController.navigate(ROUTE_CHAT)
                            },
                            onNavigateToVoiceActions = {
                                navController.navigate(ROUTE_ACTIONS_VOICE) {
                                    popUpTo(ROUTE_LIST) { saveState = true }
                                    launchSingleTop = true
                                }
                            },
                            onNavigateToActions = {
                                navController.navigate(ROUTE_ACTIONS_OPEN) {
                                    popUpTo(ROUTE_LIST) { saveState = true }
                                    launchSingleTop = true
                                }
                            },
                            onOpenDrawer = {
                                coroutineScope.launch { drawerState.open() }
                            },
                            onNavigateToSettings = {
                                navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                            },
                        )
                    }
                }

                composable(
                    route = "$ROUTE_ACTIONS?openSheet={openSheet}&$ARG_START_VOICE={$ARG_START_VOICE}&$ARG_WIDGET_QUERY={$ARG_WIDGET_QUERY}&$ARG_WIDGET_VOICE={$ARG_WIDGET_VOICE}&$ARG_DRAFT_QUERY={$ARG_DRAFT_QUERY}",
                    arguments = listOf(
                        navArgument("openSheet") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(ARG_START_VOICE) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(ARG_WIDGET_QUERY) {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = false
                        },
                        navArgument(ARG_WIDGET_VOICE) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(ARG_DRAFT_QUERY) {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = false
                        },
                    ),
                ) { backStackEntry ->
                    val openSheet = (backStackEntry.arguments?.getBoolean("openSheet") ?: false) &&
                        (backStackEntry.savedStateHandle.get<Boolean>(STATE_OPEN_SHEET_CONSUMED) != true)
                    val startVoice = (backStackEntry.arguments?.getBoolean(ARG_START_VOICE) ?: false) &&
                        (backStackEntry.savedStateHandle.get<Boolean>(STATE_START_VOICE_CONSUMED) != true)
                    // widgetQuery: baked into the route URL so it's stable in backStackEntry.arguments.
                    // savedStateHandle guards against re-execution if the composable is recomposed.
                    val widgetQuery = backStackEntry.arguments?.getString(ARG_WIDGET_QUERY)
                        ?.takeIf { it.isNotBlank() }
                        ?.takeIf { backStackEntry.savedStateHandle.get<Boolean>(STATE_WIDGET_QUERY_CONSUMED) != true }
                    val widgetVoice = if (widgetQuery != null) {
                        backStackEntry.arguments?.getBoolean(ARG_WIDGET_VOICE) ?: false
                    } else false
                    // draftQuery: baked into the route URL for Tools example prompt prefill.
                    // ActionsScreen tracks last-seen draft to prevent re-fire on recomposition.
                    val draftQuery = backStackEntry.arguments?.getString(ARG_DRAFT_QUERY)
                        ?.takeIf { it.isNotBlank() }
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ActionsScreen(
                            autoOpenSheet = openSheet,
                            autoStartVoiceCommand = startVoice,
                            initialQuery = widgetQuery,
                            initialQueryIsVoice = widgetVoice,
                            adbSlotReply = initialSlotReply,
                            draftQuery = draftQuery,
                            onDraftQueryConsumed = {
                                backStackEntry.arguments?.putString(ARG_DRAFT_QUERY, "")
                            },
                            onAutoOpenSheetConsumed = {
                                backStackEntry.savedStateHandle[STATE_OPEN_SHEET_CONSUMED] = true
                                backStackEntry.arguments?.putBoolean("openSheet", false)
                            },
                            onAutoStartVoiceConsumed = {
                                backStackEntry.savedStateHandle[STATE_START_VOICE_CONSUMED] = true
                                backStackEntry.arguments?.putBoolean(ARG_START_VOICE, false)
                            },
                            onInitialQueryConsumed = {
                                backStackEntry.savedStateHandle[STATE_WIDGET_QUERY_CONSUMED] = true
                                backStackEntry.arguments?.putString(ARG_WIDGET_QUERY, "")
                            },
                            onNavigateToChat = { query, speakResponse ->
                                navController.navigate(
                                    buildChatRoute(
                                        initialQuery = query,
                                        minimalContext = true,
                                        speakResponse = speakResponse,
                                    ),
                                )
                            },
                            onNewConversation = {
                                navController.navigate(ROUTE_CHAT)
                            },
                            onOpenDrawer = {
                                coroutineScope.launch { drawerState.open() }
                            },
                            onNavigateToSettings = {
                                navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                            },
                            onNavigateToAppPermissions = {
                                navController.navigate(ROUTE_APP_PERMISSIONS) { launchSingleTop = true }
                            },
                        )
                    }
                }

                composable(
                    route = "$ROUTE_CHAT?$ARG_INITIAL_QUERY={$ARG_INITIAL_QUERY}&$ARG_MINIMAL_CONTEXT={$ARG_MINIMAL_CONTEXT}&$ARG_SPEAK_RESPONSE={$ARG_SPEAK_RESPONSE}",
                    arguments = listOf(
                        navArgument(ARG_INITIAL_QUERY) {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = false
                        },
                        navArgument(ARG_MINIMAL_CONTEXT) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(ARG_SPEAK_RESPONSE) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { backStackEntry ->
                    val initialQuery = backStackEntry.arguments?.getString(ARG_INITIAL_QUERY)
                        ?.takeIf { it.isNotBlank() }
                    val speakResponse = backStackEntry.arguments?.getBoolean(ARG_SPEAK_RESPONSE) ?: false
                    ChatScreen(
                        conversationId = null,
                        initialQuery = initialQuery,
                        speakInitialResponse = speakResponse,
                        onBack = { navController.popBackOrNavigateHome() },
                        onNewConversation = {
                            navController.navigate(ROUTE_CHAT) {
                                popUpTo(ROUTE_CHAT) { inclusive = true }
                            }
                        },
                        onNavigateToList = {
                            navController.navigate(ROUTE_LIST) {
                                popUpTo(ROUTE_LIST) { inclusive = true }
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(ROUTE_SETTINGS)
                        },
                        onNavigateToModelManagement = {
                            navController.navigate(ROUTE_MODEL_MANAGEMENT)
                        },
                    )
                }

                composable(
                    route = "$ROUTE_CHAT/{$ARG_CONVERSATION_ID}",
                    arguments = listOf(navArgument(ARG_CONVERSATION_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val conversationId = backStackEntry.arguments?.getString(ARG_CONVERSATION_ID)
                    ChatScreen(
                        conversationId = conversationId,
                        onBack = { navController.popBackOrNavigateHome() },
                        onNewConversation = {
                            navController.navigate(ROUTE_CHAT)
                        },
                        onNavigateToList = { navController.popBackOrNavigateHome() },
                        onNavigateToSettings = {
                            navController.navigate(ROUTE_SETTINGS)
                        },
                        onNavigateToModelManagement = {
                            navController.navigate(ROUTE_MODEL_MANAGEMENT)
                        },
                    )
                }

                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToUserProfile = {
                            navController.navigate(ROUTE_USER_PROFILE)
                        },
                        onNavigateToMemory = {
                            navController.navigate(ROUTE_MEMORY)
                        },
                        onNavigateToVoice = {
                            navController.navigate(ROUTE_VOICE)
                        },
                        onNavigateToModelSettings = {
                            navController.navigate(ROUTE_MODEL_SETTINGS)
                        },
                        onNavigateToModelManagement = { preferred ->
                            val route = "settings/model_management?scrollTo=$preferred"
                            navController.navigate(route)
                        },
                        onNavigateToChatPreferences = {
                            navController.navigate(ROUTE_CHAT_PREFERENCES)
                        },
                        onNavigateToAbout = {
                            navController.navigate(ROUTE_ABOUT)
                        },
                        onNavigateToAppPermissions = {
                            navController.navigate(ROUTE_APP_PERMISSIONS)
                        },
                        onNavigateToClockSettings = {
                            navController.navigate(ROUTE_CLOCK_SETTINGS) {
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(ROUTE_USER_PROFILE) {
                    UserProfileScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_CHAT_PREFERENCES) {
                    ChatPreferencesScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_MEMORY) {
                    MemoryScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_MEAL_PLANS) {
                    MealPlansScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onStartNewMealPlan = {
                            navController.navigate(buildNewMealPlanChatRoute())
                        },
                    )
                }
                composable(ROUTE_NOTES) {
                    NotesScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onEditNote = { noteId ->
                            navController.navigate("$ROUTE_NOTES/$noteId")
                        },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(
                    ROUTE_NOTE_DETAIL,
                    arguments = listOf(navArgument(ARG_NOTE_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val noteId = backStackEntry.arguments?.getLong(ARG_NOTE_ID) ?: return@composable
                    NoteDetailScreen(
                        noteId = noteId,
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_IMPORTANT_DATES) {
                    ImportantDatesScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(ROUTE_VOICE) {
                    VoiceScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_MODEL_SETTINGS) {
                    ModelSettingsScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(
                    ROUTE_MODEL_MANAGEMENT,
                    arguments = listOf(navArgument(ARG_SCROLL_TO) {
                        type = NavType.BoolType
                        defaultValue = false
                    }),
                ) { backStackEntry ->
                    val scrollTo = backStackEntry.arguments?.getBoolean(ARG_SCROLL_TO) ?: false
                    ModelManagementScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        scrollToConversationModel = scrollTo,
                    )
                }

                composable(ROUTE_ABOUT) {
                    AboutScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        versionName = com.kernel.ai.BuildConfig.VERSION_NAME,
                        versionCode = com.kernel.ai.BuildConfig.VERSION_CODE,
                        buildType = com.kernel.ai.BuildConfig.BUILD_TYPE,
                        gitSha = com.kernel.ai.BuildConfig.GIT_SHA,
                        buildTimestamp = com.kernel.ai.BuildConfig.BUILD_TIMESTAMP,
                    )
                }

                composable(ROUTE_APP_PERMISSIONS) {
                    AppPermissionsScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_CONTACT_ALIASES) {
                    ContactAliasesScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_SCHEDULED_ALARMS) {
                    // Redirected to the unified Clock screen (#574 / #742)
                    SidePanelScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(
                    route = "$ROUTE_SIDE_PANEL?tab={tab}",
                    arguments = listOf(
                        navArgument("tab") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { backStackEntry ->
                    val tabParam = backStackEntry.arguments?.getString("tab") ?: ""
                    val initialTab = tabParam.ifBlank { null }
                    SidePanelScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToClockSettings = {
                            navController.navigate(ROUTE_CLOCK_SETTINGS) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                        initialTab = initialTab,
                    )
                }

                composable(ROUTE_CLOCK_SETTINGS) {
                    ClockSettingsScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                    )
                }

                composable(ROUTE_LISTS) {
                    ListsScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onOpenList = { listId ->
                            navController.navigate("lists/$listId")
                        },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(
                    route = ROUTE_LIST_ITEMS,
                    arguments = listOf(navArgument(ARG_LIST_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val listId = backStackEntry.arguments?.getLong(ARG_LIST_ID)
                        ?: return@composable
                    ListItemsScreen(
                        listId = listId,
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(
                    route = "$ROUTE_CONVERT?tab={tab}",
                    arguments = listOf(
                        navArgument("tab") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { backStackEntry ->
                    val tabParam = backStackEntry.arguments?.getString("tab") ?: ""
                    val initialTab = mapQueryParamToConvertTab(tabParam)
                    ConvertScreen(
                        onBack = { navController.popBackOrNavigateHome() },
                        onNavigateToVoiceActions = {
                            navController.navigate(ROUTE_ACTIONS_VOICE) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                        initialTab = initialTab,
                    )
                }
                composable(ROUTE_TOOLS) {
                    val scope = rememberCoroutineScope()
                    val favEntities by favouriteShortcutRepository?.observeAll()
                        ?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
                    val toolsFavouriteIds = favEntities.map { it.id }.toSet()
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ToolsHubScreen(
                            onOpenDrawer = {
                                coroutineScope.launch { drawerState.open() }
                            },
                            onNavigateToRoute = { route ->
                                val didNavigate = navController.navigateToToolsDestination(route)
                                val shortcut = ShortcutRegistry.byRoute(route)
                                    ?.takeIf { didNavigate }
                                    ?.takeIf { shouldRecordRecentShortcut(it, toolsFavouriteIds) }
                                if (shortcut != null) {
                                    scope.launch {
                                        recentShortcutTracker?.record(shortcut.id)
                                    }
                                }
                            },
                            onNavigateToSettings = {
                                navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                            },
                            onOpenPrompt = { prompt ->
                                navController.navigate(buildActionsDraftRoute(prompt)) {
                                    launchSingleTop = true
                                }
                            },
                            favouriteIds = toolsFavouriteIds,
                            onToggleFavourite = { shortcutId ->
                                scope.launch {
                                    favouriteShortcutRepository?.toggle(shortcutId)
                                }
                            },
                        )
                    }
                }

                composable(ROUTE_TOOLS_LEARN) {
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ToolsLearnScreen(
                            onBack = { navController.popBackOrNavigateHome() },
                            onOpenPrompt = { prompt ->
                                navController.navigate(buildActionsDraftRoute(prompt)) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dynamic drawer content that shows favourites, recents, defaults, and settings.
 *
 * Uses [buildDrawerSections] to produce a sectioned layout with labelled headers
 * for Favourites, Recently Used, More Shortcuts, and Settings.
 */
@Composable
internal fun DrawerContent(
    navController: NavHostController,
    drawerState: androidx.compose.material3.DrawerState,
    currentBaseRoute: String?,
    currentTab: String?,
    favouriteShortcutRepository: FavouriteShortcutRepository?,
    recentShortcutTracker: RecentShortcutTracker?,
) {
    val scope = rememberCoroutineScope()

    // Collect favourites and recents if the repositories are provided
    val favouriteIds by when (favouriteShortcutRepository) {
        null -> remember { mutableStateOf(emptyList<FavouriteShortcutEntity>()) }
        else -> favouriteShortcutRepository.observeAll().collectAsState(initial = emptyList())
    }
    val recentIds by when (recentShortcutTracker) {
        null -> remember { mutableStateOf(emptyList<RecentShortcutEntity>()) }
        else -> recentShortcutTracker.observeAll().collectAsState(initial = emptyList())
    }

    val drawerFavouriteIds = favouriteIds.map { it.id }.toSet()

    // Compute the sectioned display model
    val sections by remember(drawerFavouriteIds, recentIds) {
        derivedStateOf {
            buildDrawerSections(
                favouriteIds = drawerFavouriteIds.toList(),
                recentIds = recentIds.map { it.id },
            )
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .testTag("drawer_content_scroll"),
    ) {
        // Header
        Text(
            text = "Jandal",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.padding(4.dp))

        sections.forEach { section ->
            // Skip empty sections without helper content (e.g. empty Recently Used)
            if (section.items.isEmpty() && section.emptyMessage == null) return@forEach
            // Section header (null for Settings section)
            if (section.header != null) {
                Text(
                    text = section.header,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .testTag("drawer_section_${section.header.lowercase().replace(' ', '_')}"),
                )
            }

            if (section.items.isEmpty() && section.emptyMessage != null) {
                // Empty state helper row
                Text(
                    text = section.emptyMessage,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .testTag("drawer_favourites_empty"),
                )
            }

            if (section.header == null && section.items.any { it.isSettings }) {
                // Divider before Settings
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            section.items.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    selected = isDrawerShortcutSelected(currentBaseRoute, currentTab, item.route),
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            // Record recent (defence-in-depth: honour canRecordRecent)
                            val didNavigate = navController.navigateToDrawerDestination(item.route)
                            val shouldRecordRecent = didNavigate &&
                                recentShortcutTracker != null &&
                                shouldRecordRecentShortcut(item, drawerFavouriteIds)
                            if (shouldRecordRecent) {
                                recentShortcutTracker.record(item.id)
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_item_${item.id}"),
                )
            }
        }
    }
}

/** True when opening [shortcut] should mutate Recently Used storage. */
internal fun shouldRecordRecentShortcut(
    shortcut: ShortcutDef,
    favouriteIds: Set<String>,
): Boolean = shortcut.canRecordRecent && !shortcut.isSettings && shortcut.id !in favouriteIds

/**
 * Build the ordered list of drawer shortcut items.
 *
 * Order: favourites → recents (no duplicates with favourites) → defaults (fill remaining) → settings (pinned).
 */
internal fun buildDrawerItems(
    favouriteIds: List<String>,
    recentIds: List<String>,
): List<ShortcutDef> {
    val result = mutableListOf<ShortcutDef>()
    val addedIds = mutableSetOf<String>()

    // 1. Favourite shortcuts (in order)
    for (id in favouriteIds) {
        val def = ShortcutRegistry.byId(id)
        if (def != null && def.isSettings.not()) {
            result.add(def)
            addedIds.add(id)
        }
    }

    // 2. Recently used shortcuts (deduped against favourites)
    for (id in recentIds) {
        if (id in addedIds || id == ShortcutRegistry.settings.id) continue
        val def = ShortcutRegistry.byId(id)
        if (def != null) {
            result.add(def)
            addedIds.add(id)
        }
    }

    // 3. Default shortcuts (fill gaps, deduped against already-added)
    for (def in ShortcutRegistry.drawerDefaults) {
        if (def.id in addedIds) continue
        result.add(def)
        addedIds.add(def.id)
    }

    // 4. Settings pinned at the bottom with a divider
    result.add(ShortcutRegistry.settings)

    return result
}

/**
 * A section in the quick-access drawer.
 *
 * @param header Label shown as section header, or null for unlabeled sections (e.g. Settings).
 * @param items Shortcuts in this section.
 * @param emptyMessage Shown when [items] is empty and this is the Favourites section.
 */
internal data class DrawerSection(
    val header: String?,
    val items: List<ShortcutDef>,
    val emptyMessage: String? = null,
)

/**
 * Build the sectioned drawer shortcut model.
 *
 * Sections:
 * 1. Favourites — only favourite shortcuts
 * 2. Recently Used — non-favourite recents
 * 3. More Shortcuts — defaults not already shown in favourites or recents
 * 4. Settings — always pinned last with a divider
 *
 * Each section header has a stable [testTag] for UI test assertions.
 */
internal fun buildDrawerSections(
    favouriteIds: List<String>,
    recentIds: List<String>,
): List<DrawerSection> {
    val favouriteSet = favouriteIds.toSet()

    // 1. Favourites
    val favouriteDefs = favouriteIds.mapNotNull { ShortcutRegistry.byId(it) }
        .filter { !it.isSettings }

    // 2. Recents (non-favourite)
    val recentDefs = recentIds
        .filter { it !in favouriteSet }
        .mapNotNull { ShortcutRegistry.byId(it) }
        .filter { !it.isSettings }

    // 3. More Shortcuts — from defaults, deduped against visible favourites + recents
    val alreadyShown = (favouriteDefs + recentDefs).map { it.id }.toSet()
    val moreDefs = ShortcutRegistry.drawerDefaults
        .filter { it.id !in alreadyShown }

    val sections = mutableListOf<DrawerSection>()

    sections.add(
        DrawerSection(
            header = "Favourites",
            items = favouriteDefs,
            emptyMessage = if (favouriteDefs.isEmpty()) "Star tools from Tools to add them here." else null,
        )
    )

    sections.add(
        DrawerSection(
            header = "Recently Used",
            items = recentDefs,
        )
    )

    sections.add(
        DrawerSection(
            header = "More Shortcuts",
            items = moreDefs,
        )
    )

    // 4. Settings (pinned last)
    sections.add(
        DrawerSection(
            header = null,
            items = listOf(ShortcutRegistry.settings),
        )
    )

    return sections
}
