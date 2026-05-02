package com.kernel.ai.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kernel.ai.feature.chat.ActionsScreen
import com.kernel.ai.feature.chat.ChatScreen
import com.kernel.ai.feature.chat.ConversationListScreen
import com.kernel.ai.feature.settings.AboutScreen
import com.kernel.ai.feature.settings.ContactAliasesScreen
import com.kernel.ai.feature.settings.ListItemsScreen
import com.kernel.ai.feature.settings.ListsScreen
import com.kernel.ai.feature.settings.MemoryScreen
import com.kernel.ai.feature.settings.ModelManagementScreen
import com.kernel.ai.feature.settings.ModelSettingsScreen
import com.kernel.ai.feature.settings.ScheduledAlarmsScreen
import com.kernel.ai.feature.settings.SettingsScreen
import com.kernel.ai.feature.settings.SidePanelScreen
import com.kernel.ai.feature.settings.UserProfileScreen
import com.kernel.ai.feature.settings.VoiceScreen
import kotlinx.coroutines.launch

private const val ROUTE_LIST = "conversation_list"
private const val ROUTE_ACTIONS = "actions"
private const val ROUTE_ACTIONS_OPEN = "actions?openSheet=true"
private const val ROUTE_ACTIONS_VOICE = "actions?startVoice=true"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_USER_PROFILE = "settings/user_profile"
private const val ROUTE_MEMORY = "settings/memory"
private const val ROUTE_VOICE = "settings/voice"
private const val ROUTE_MODEL_SETTINGS = "settings/model_settings"
private const val ROUTE_MODEL_MANAGEMENT = "settings/model_management?scrollTo={scrollTo}"
private const val ARG_SCROLL_TO = "scrollTo"
private const val ROUTE_ABOUT = "settings/about"
private const val ROUTE_CONTACT_ALIASES = "settings/contact_aliases"
private const val ROUTE_SCHEDULED_ALARMS = "settings/scheduled_alarms"
private const val ROUTE_SIDE_PANEL = "settings/side_panel"
private const val ROUTE_LISTS = "lists"
private const val ROUTE_LIST_ITEMS = "lists/{listName}"
private const val ARG_LIST_NAME = "listName"
private const val ARG_CONVERSATION_ID = "conversationId"
private const val ARG_INITIAL_QUERY = "initialQuery"
private const val ARG_START_VOICE = "startVoice"
private const val STATE_OPEN_SHEET_CONSUMED = "openSheetConsumed"
private const val STATE_START_VOICE_CONSUMED = "startVoiceConsumed"

/** Routes that show the bottom navigation bar. */
private val BOTTOM_NAV_ROUTES = setOf(ROUTE_LIST, ROUTE_ACTIONS)

@Composable
fun KernelNavHost(
    initialChatQuery: String? = null,
    initialQuickActionQuery: String? = null,
    initialSlotReply: String? = null,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentBaseRoute = currentRoute?.substringBefore('?')

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // ADB test harness: navigate to chat from any screen when chat_input extra is delivered
    LaunchedEffect(initialChatQuery) {
        if (!initialChatQuery.isNullOrBlank()) {
            val encoded = Uri.encode(initialChatQuery)
            navController.navigate("$ROUTE_CHAT?$ARG_INITIAL_QUERY=$encoded") {
                popUpTo(ROUTE_LIST)
            }
        }
    }

    // ADB test harness: navigate to Actions tab when quick_action_input extra is delivered
    LaunchedEffect(initialQuickActionQuery) {
        if (!initialQuickActionQuery.isNullOrBlank()) {
            navController.navigate(ROUTE_ACTIONS) {
                popUpTo(ROUTE_LIST)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only allow swipe-to-open on main bottom-nav screens
        gesturesEnabled = currentBaseRoute in BOTTOM_NAV_ROUTES,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Jandal",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                )
                HorizontalDivider()
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                NavigationDrawerItem(
                    label = { Text("Lists") },
                    icon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                    selected = currentBaseRoute == ROUTE_LISTS,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate(ROUTE_LISTS) {
                            popUpTo(ROUTE_LIST) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("Timers & Alarms") },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    selected = currentBaseRoute == ROUTE_SIDE_PANEL,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate(ROUTE_SIDE_PANEL) {
                            popUpTo(ROUTE_LIST) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    label = { Text("People & Contacts") },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    selected = currentBaseRoute == ROUTE_CONTACT_ALIASES,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate(ROUTE_CONTACT_ALIASES) {
                            popUpTo(ROUTE_LIST) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    selected = currentBaseRoute == ROUTE_SETTINGS,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate(ROUTE_SETTINGS) {
                            popUpTo(ROUTE_LIST) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                if (currentBaseRoute in BOTTOM_NAV_ROUTES) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentBaseRoute == ROUTE_LIST,
                            onClick = {
                                if (currentBaseRoute != ROUTE_LIST) {
                                    navController.navigate(ROUTE_LIST) {
                                        popUpTo(ROUTE_LIST) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            icon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                            label = { Text("Chats") },
                        )
                        NavigationBarItem(
                            selected = currentBaseRoute == ROUTE_ACTIONS,
                            onClick = {
                                if (currentBaseRoute != ROUTE_ACTIONS) {
                                    navController.navigate(ROUTE_ACTIONS) {
                                        popUpTo(ROUTE_LIST) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                            label = { Text("Actions") },
                        )
                    }
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
                        )
                    }
                }

                composable(
                    route = "$ROUTE_ACTIONS?openSheet={openSheet}&$ARG_START_VOICE={$ARG_START_VOICE}",
                    arguments = listOf(
                        navArgument("openSheet") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(ARG_START_VOICE) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { backStackEntry ->
                    val openSheet = (backStackEntry.arguments?.getBoolean("openSheet") ?: false) &&
                        (backStackEntry.savedStateHandle.get<Boolean>(STATE_OPEN_SHEET_CONSUMED) != true)
                    val startVoice = (backStackEntry.arguments?.getBoolean(ARG_START_VOICE) ?: false) &&
                        (backStackEntry.savedStateHandle.get<Boolean>(STATE_START_VOICE_CONSUMED) != true)
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ActionsScreen(
                            autoOpenSheet = openSheet,
                            autoStartVoiceCommand = startVoice,
                            initialQuery = initialQuickActionQuery,
                            adbSlotReply = initialSlotReply,
                            onAutoOpenSheetConsumed = {
                                backStackEntry.savedStateHandle[STATE_OPEN_SHEET_CONSUMED] = true
                                backStackEntry.arguments?.putBoolean("openSheet", false)
                            },
                            onAutoStartVoiceConsumed = {
                                backStackEntry.savedStateHandle[STATE_START_VOICE_CONSUMED] = true
                                backStackEntry.arguments?.putBoolean(ARG_START_VOICE, false)
                            },
                            onNavigateToChat = { query ->
                                val encoded = Uri.encode(query)
                                navController.navigate("$ROUTE_CHAT?$ARG_INITIAL_QUERY=$encoded")
                            },
                            onNewConversation = {
                                navController.navigate(ROUTE_CHAT)
                            },
                            onOpenDrawer = {
                                coroutineScope.launch { drawerState.open() }
                            },
                        )
                    }
                }

                composable(
                    route = "$ROUTE_CHAT?$ARG_INITIAL_QUERY={$ARG_INITIAL_QUERY}",
                    arguments = listOf(navArgument(ARG_INITIAL_QUERY) {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = false
                    }),
                ) { backStackEntry ->
                    val initialQuery = backStackEntry.arguments?.getString(ARG_INITIAL_QUERY)
                        ?.takeIf { it.isNotBlank() }
                    ChatScreen(
                        conversationId = null,
                        initialQuery = initialQuery,
                        onBack = { navController.popBackStack() },
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
                    )
                }

                composable(
                    route = "$ROUTE_CHAT/{$ARG_CONVERSATION_ID}",
                    arguments = listOf(navArgument(ARG_CONVERSATION_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val conversationId = backStackEntry.arguments?.getString(ARG_CONVERSATION_ID)
                    ChatScreen(
                        conversationId = conversationId,
                        onBack = { navController.popBackStack() },
                        onNewConversation = {
                            navController.navigate(ROUTE_CHAT)
                        },
                        onNavigateToList = {
                            navController.popBackStack()
                        },
                        onNavigateToSettings = {
                            navController.navigate(ROUTE_SETTINGS)
                        },
                    )
                }

                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
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
                        onNavigateToAbout = {
                            navController.navigate(ROUTE_ABOUT)
                        },
                    )
                }

                composable(ROUTE_USER_PROFILE) {
                    UserProfileScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_MEMORY) {
                    MemoryScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_VOICE) {
                    VoiceScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_MODEL_SETTINGS) {
                    ModelSettingsScreen(
                        onBack = { navController.popBackStack() },
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
                        onBack = { navController.popBackStack() },
                        scrollToConversationModel = scrollTo,
                    )
                }

                composable(ROUTE_ABOUT) {
                    AboutScreen(
                        onBack = { navController.popBackStack() },
                        versionName = com.kernel.ai.BuildConfig.VERSION_NAME,
                        versionCode = com.kernel.ai.BuildConfig.VERSION_CODE,
                        buildType = com.kernel.ai.BuildConfig.BUILD_TYPE,
                        gitSha = com.kernel.ai.BuildConfig.GIT_SHA,
                        buildTimestamp = com.kernel.ai.BuildConfig.BUILD_TIMESTAMP,
                    )
                }

                composable(ROUTE_CONTACT_ALIASES) {
                    ContactAliasesScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_SCHEDULED_ALARMS) {
                    // Redirected to the unified Timers & Alarms screen (#574)
                    SidePanelScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_SIDE_PANEL) {
                    SidePanelScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_LISTS) {
                    ListsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenList = { listName ->
                            navController.navigate("lists/${android.net.Uri.encode(listName)}")
                        },
                    )
                }

                composable(
                    route = ROUTE_LIST_ITEMS,
                    arguments = listOf(navArgument(ARG_LIST_NAME) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val listName = backStackEntry.arguments?.getString(ARG_LIST_NAME)
                        ?.let { android.net.Uri.decode(it) } ?: return@composable
                    ListItemsScreen(
                        listName = listName,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
