package com.kernel.ai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kernel.ai.feature.chat.ChatScreen

@Composable
fun KernelNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "chat"
    ) {
        composable("chat") {
            ChatScreen()
        }
        composable("settings") {
            // SettingsScreen — implemented in Phase 2+
        }
        composable("onboarding") {
            // OnboardingScreen — implemented in Phase 1.3
        }
    }
}
