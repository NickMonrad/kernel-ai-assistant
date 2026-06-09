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
            "tools_row_learn",
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
    fun toolsHub_rowLearnNavigatesToToolsLearn() {
        assertRowNavigatesTo("tools_row_learn", ROUTE_TOOLS_LEARN)
    }

    @Test
    fun toolsHub_learnRowIsFirstRow() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }

        // tools_row_learn should be visible without scrolling
        composeTestRule.onNodeWithTag("tools_row_learn").assertIsDisplayed()
        composeTestRule.onNodeWithText("Learn what Jandal can do").assertIsDisplayed()
        composeTestRule.onNodeWithText("Example prompts for actions, planning, weather, maps, media, and more").assertIsDisplayed()
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

    @Test
    fun toolsLearnScreen_showsHelperCopy() {
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_learn_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_learn_helper_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_learn_privacy_note").assertIsDisplayed()
    }

    @Test
    fun toolsLearnScreen_showsDefaultExamplesCollapsed() {
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = {},
            )
        }

        // Default examples should be visible
        val screenNode = composeTestRule.onNodeWithTag("tools_learn_screen")
        screenNode.performScrollToNode(hasTestTag("tools_learn_lists_add_milk"))
        composeTestRule.onNodeWithTag("tools_learn_lists_add_milk").assertIsDisplayed()
        screenNode.performScrollToNode(hasTestTag("tools_learn_meal_plan_dinners_week"))
        composeTestRule.onNodeWithTag("tools_learn_meal_plan_dinners_week").assertIsDisplayed()
        screenNode.performScrollToNode(hasTestTag("tools_learn_weather_current"))
        composeTestRule.onNodeWithTag("tools_learn_weather_current").assertIsDisplayed()
    }

    @Test
    fun toolsLearnScreen_expandMealPlanningShowsMoreExamples() {
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = {},
            )
        }

        val screenNode = composeTestRule.onNodeWithTag("tools_learn_screen")
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_meal_planning"))
        composeTestRule.onNodeWithTag("tools_learn_view_more_meal_planning", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        screenNode.performScrollToNode(hasTestTag("tools_learn_meal_plan_family"))
        composeTestRule.onNodeWithTag("tools_learn_meal_plan_family").assertIsDisplayed()
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_meal_planning"))
        composeTestRule.onNodeWithText("Show less").assertIsDisplayed()
    }

    @Test
    fun toolsLearnScreen_expandWeatherShowsMoreExamples() {
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = {},
            )
        }

        val screenNode = composeTestRule.onNodeWithTag("tools_learn_screen")
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_weather"))
        composeTestRule.onNodeWithTag("tools_learn_view_more_weather", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        screenNode.performScrollToNode(hasTestTag("tools_learn_weather_wellington"))
        composeTestRule.onNodeWithTag("tools_learn_weather_wellington").assertIsDisplayed()
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_weather"))
        composeTestRule.onNodeWithText("Show less").assertIsDisplayed()
    }

    @Test
    fun toolsLearnScreen_tappingExampleTriggersOnOpenPrompt() {
        var lastPrompt = ""
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = { lastPrompt = it },
            )
        }

        val screenNode = composeTestRule.onNodeWithTag("tools_learn_screen")
        screenNode.performScrollToNode(hasTestTag("tools_learn_time_timer_10"))
        composeTestRule.onNodeWithTag("tools_learn_time_timer_10", useUnmergedTree = true).performClick()

        assertEquals("Set a timer for 10 minutes", lastPrompt)
    }

    @Test
    fun toolsLearnScreen_collapseAfterExpand() {
        composeTestRule.setContent {
            ToolsLearnScreen(
                onBack = {},
                onOpenPrompt = {},
            )
        }

        val screenNode = composeTestRule.onNodeWithTag("tools_learn_screen")
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_weather"))
        composeTestRule.onNodeWithTag("tools_learn_view_more_weather", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_weather"))
        composeTestRule.onNodeWithText("Show less").performClick()
        composeTestRule.waitForIdle()

        // After collapse, weather section shows "View more" again
        screenNode.performScrollToNode(hasTestTag("tools_learn_view_more_weather"))
        composeTestRule.onNodeWithTag("tools_learn_view_more_weather").assertIsDisplayed()
    }

    @Test
    fun toolsHub_draftRoute_safeConstruction() {
        val route = buildActionsDraftRoute("Convert 2 cups to mL")
        assertTrue(route.startsWith("actions?openSheet=true"))
        assertTrue(route.contains("draftQuery=Convert%202%20cups%20to%20mL"))
        assertEquals(false, route.contains("widgetQuery"))
    }

    @Test
    fun toolsHub_draftRoute_encodesSpecialCharacters() {
        val route = buildActionsDraftRoute("add milk & eggs")
        assertTrue(route.contains("draftQuery=add%20milk%20%26%20eggs"))
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