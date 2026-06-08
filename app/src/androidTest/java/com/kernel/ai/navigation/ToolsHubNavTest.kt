package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolsHubNavTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bottomNav_showsChatsActionsAndTools() {
        composeTestRule.setContent {
            PrimaryBottomBar(
                currentBaseRoute = "conversation_list",
                onNavigateToRoute = {},
            )
        }

        composeTestRule.onNodeWithTag("bottom_nav_chats").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_actions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_tools").assertIsDisplayed()
    }

    @Test
    fun bottomNav_marksToolsSelectedWhenOnToolsRoute() {
        composeTestRule.setContent {
            PrimaryBottomBar(
                currentBaseRoute = "tools",
                onNavigateToRoute = {},
            )
        }

        composeTestRule.onNodeWithTag("bottom_nav_tools").assertIsSelected()
    }

    @Test
    fun bottomNav_clickingActionsToolsAndChatsUpdatesVisibleScreen() {
        composeTestRule.setContent {
            BottomNavHarness()
        }

        composeTestRule.onNodeWithText("Chats Screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_actions").performClick()
        composeTestRule.onNodeWithText("Actions Screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_tools").performClick()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_chats").performClick()
        composeTestRule.onNodeWithText("Chats Screen").assertIsDisplayed()
    }

    @Test
    fun toolsHub_rendersExpectedGroupsAndRows() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }

        listOf(
            "tools_group_productivity",
            "tools_group_time_planning",
            "tools_group_people",
            "tools_group_utilities",
            "tools_group_app_setup",
            "tools_row_lists",
            "tools_row_notes",
            "tools_row_meal_plans",
            "tools_row_clock",
            "tools_row_important_dates",
            "tools_row_people_contacts",
            "tools_row_convert",
            "tools_row_settings",
            "tools_row_voice",
            "tools_row_models",
            "tools_row_permissions",
            "tools_group_personalisation",
            "tools_row_user_profile",
            "tools_row_memory",
            "tools_row_chat_preferences",
            "tools_row_about",
        ).forEach { tag ->
            assertTagExists(tag)
        }

        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasText("Archive, themes, wallpaper, and copy options"))
        composeTestRule.onNodeWithText("Archive, themes, wallpaper, and copy options").assertIsDisplayed()
    }

    @Test
    fun toolsHub_rowListsNavigatesToLists() {
        assertRowNavigatesTo("tools_row_lists", ROUTE_LISTS)
    }

    @Test
    fun toolsHub_rowNotesNavigatesToNotes() {
        assertRowNavigatesTo("tools_row_notes", ROUTE_NOTES)
    }

    @Test
    fun toolsHub_rowMealPlansNavigatesToMealPlans() {
        assertRowNavigatesTo("tools_row_meal_plans", ROUTE_MEAL_PLANS)
    }

    @Test
    fun toolsHub_rowClockNavigatesToClock() {
        assertRowNavigatesTo("tools_row_clock", ROUTE_SIDE_PANEL)
    }

    @Test
    fun toolsHub_rowImportantDatesNavigatesToImportantDates() {
        assertRowNavigatesTo("tools_row_important_dates", ROUTE_IMPORTANT_DATES)
    }

    @Test
    fun toolsHub_rowPeopleNavigatesToContacts() {
        assertRowNavigatesTo("tools_row_people_contacts", ROUTE_CONTACT_ALIASES)
    }

    @Test
    fun toolsHub_rowConvertNavigatesToConvert() {
        assertRowNavigatesTo("tools_row_convert", ROUTE_CONVERT)
    }

    @Test
    fun toolsHub_rowUserProfileNavigatesToUserProfile() {
        assertRowNavigatesTo("tools_row_user_profile", ROUTE_USER_PROFILE)
    }

    @Test
    fun toolsHub_rowMemoryNavigatesToMemory() {
        assertRowNavigatesTo("tools_row_memory", ROUTE_MEMORY)
    }

    @Test
    fun toolsHub_rowVoiceNavigatesToVoice() {
        assertRowNavigatesTo("tools_row_voice", ROUTE_VOICE)
    }

    @Test
    fun toolsHub_rowChatPreferencesNavigatesToChatPreferences() {
        assertRowNavigatesTo("tools_row_chat_preferences", ROUTE_CHAT_PREFERENCES)
    }

    @Test
    fun toolsHub_rowSettingsNavigatesToSettings() {
        assertRowNavigatesTo("tools_row_settings", ROUTE_SETTINGS)
    }

    @Test
    fun toolsHub_rowModelsNavigatesToModelManagement() {
        assertRowNavigatesTo(
            rowTag = "tools_row_models",
            expectedRoute = buildModelManagementRoute(),
        )
    }

    @Test
    fun toolsHub_rowPermissionsNavigatesToPermissions() {
        assertRowNavigatesTo("tools_row_permissions", ROUTE_APP_PERMISSIONS)
    }

    @Test
    fun toolsHub_rowAboutNavigatesToAbout() {
        assertRowNavigatesTo("tools_row_about", ROUTE_ABOUT)
    }

    private fun assertRowNavigatesTo(
        rowTag: String,
        expectedRoute: String,
    ) {
        var lastRoute = ""
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = { lastRoute = it },
            )
        }

        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag(rowTag))
        composeTestRule.onNodeWithTag(rowTag, useUnmergedTree = true).performClick()

        assertEquals(expectedRoute, lastRoute)
    }

    private fun assertTagExists(tag: String) {
        assertTrue(
            "Expected semantics node with tag '$tag'",
            composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}

@androidx.compose.runtime.Composable
private fun BottomNavHarness() {
    var currentRoute by remember { mutableStateOf("conversation_list") }

    Column {
        PrimaryBottomBar(
            currentBaseRoute = currentRoute,
            onNavigateToRoute = { currentRoute = it },
        )

        when (currentRoute) {
            "conversation_list" -> Text("Chats Screen")
            "actions" -> Text("Actions Screen")
            "tools" -> ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }
    }
}
