package com.kernel.ai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kernel.ai.feature.chat.ChatScreen
import com.kernel.ai.feature.chat.ConversationListScreen

private const val ROUTE_LIST = "conversation_list"
private const val ROUTE_CHAT = "chat"
private const val ARG_CONVERSATION_ID = "conversationId"

@Composable
fun KernelNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_LIST,
    ) {
        composable(ROUTE_LIST) {
            ConversationListScreen(
                onOpenConversation = { id ->
                    navController.navigate("$ROUTE_CHAT/$id")
                },
                onNewConversation = {
                    navController.navigate(ROUTE_CHAT)
                },
            )
        }

        // New conversation (no conversationId arg)
        composable(ROUTE_CHAT) {
            ChatScreen(
                conversationId = null,
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
                onNewConversation = {
                    navController.navigate(ROUTE_CHAT)
                },
                onNavigateToList = {
                    navController.popBackStack()
                },
            )
        }
    }
}
