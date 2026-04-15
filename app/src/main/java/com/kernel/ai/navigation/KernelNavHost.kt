package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.kernel.ai.feature.settings.MemoryScreen
import com.kernel.ai.feature.settings.ModelSettingsScreen
import com.kernel.ai.feature.settings.SettingsScreen
import com.kernel.ai.feature.settings.UserProfileScreen

private const val ROUTE_LIST = "conversation_list"
private const val ROUTE_ACTIONS = "actions"
private const val ROUTE_ACTIONS_OPEN = "actions?openSheet=true"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_USER_PROFILE = "settings/user_profile"
private const val ROUTE_MEMORY = "settings/memory"
private const val ROUTE_MODEL_SETTINGS = "settings/model_settings"
private const val ROUTE_ABOUT = "settings/about"
private const val ARG_CONVERSATION_ID = "conversationId"

/** Routes that show the bottom navigation bar. */
private val BOTTOM_NAV_ROUTES = setOf(ROUTE_LIST, ROUTE_ACTIONS)

@Composable
fun KernelNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in BOTTOM_NAV_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_LIST,
                        onClick = {
                            if (currentRoute != ROUTE_LIST) {
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
                        selected = currentRoute == ROUTE_ACTIONS,
                        onClick = {
                            if (currentRoute != ROUTE_ACTIONS) {
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
        // Only apply bottom nav padding to routes that show the nav bar.
        // Chat/settings screens handle their own padding via their own Scaffold.
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
                        onNavigateToActions = {
                            navController.navigate(ROUTE_ACTIONS_OPEN) {
                                popUpTo(ROUTE_LIST) { saveState = true }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(ROUTE_SETTINGS)
                        },
                    )
                }
            }

            composable(
                route = "$ROUTE_ACTIONS?openSheet={openSheet}",
                arguments = listOf(navArgument("openSheet") {
                    type = NavType.BoolType
                    defaultValue = false
                }),
            ) { backStackEntry ->
                val openSheet = backStackEntry.arguments?.getBoolean("openSheet") ?: false
                ActionsScreen(autoOpenSheet = openSheet)
            }

            // New conversation (no conversationId arg)
            composable(ROUTE_CHAT) {
                ChatScreen(
                    conversationId = null,
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
                )
            }

            // Existing conversation
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
                    onNavigateToModelSettings = {
                        navController.navigate(ROUTE_MODEL_SETTINGS)
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

            composable(ROUTE_MODEL_SETTINGS) {
                ModelSettingsScreen(
                    onBack = { navController.popBackStack() },
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
        }
    }
}
