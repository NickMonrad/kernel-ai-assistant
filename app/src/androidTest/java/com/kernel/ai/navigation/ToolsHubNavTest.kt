package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
        ).forEach { tag ->
            assertTagExists(tag)
        }
    }

    @Test
    fun toolsHub_rowListsNavigatesToLists() {
        assertRowNavigatesTo("tools_row_lists", "lists")
    }

    @Test
    fun toolsHub_rowClockNavigatesToClock() {
        assertRowNavigatesTo("tools_row_clock", "settings/side_panel")
    }

    @Test
    fun toolsHub_rowPeopleNavigatesToContacts() {
        assertRowNavigatesTo("tools_row_people_contacts", "settings/contact_aliases", swipeUps = 1)
    }

    @Test
    fun toolsHub_rowConvertNavigatesToConvert() {
        assertRowNavigatesTo("tools_row_convert", "convert", swipeUps = 1)
    }

    @Test
    fun toolsHub_rowSettingsNavigatesToSettings() {
        assertRowNavigatesTo("tools_row_settings", "settings", swipeUps = 2)
    }

    @Test
    fun toolsHub_rowModelsNavigatesToModelManagement() {
        assertRowNavigatesTo(
            rowTag = "tools_row_models",
            expectedRoute = "settings/model_management?scrollTo=false",
            swipeUps = 2,
        )
    }

    private fun assertRowNavigatesTo(
        rowTag: String,
        expectedRoute: String,
        swipeUps: Int = 0,
    ) {
        var lastRoute = ""
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = { lastRoute = it },
            )
        }

        repeat(swipeUps) {
            composeTestRule.onNodeWithTag("tools_screen").performTouchInput { swipeUp() }
        }
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
