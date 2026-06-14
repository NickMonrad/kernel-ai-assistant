package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before

@RunWith(AndroidJUnit4::class)
class ToolsHubNavTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("tools_hub", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

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
        composeTestRule.onNodeWithText("Example prompts to get started").assertIsDisplayed()
    }

    @Test
    fun toolsHub_learnCollapseHidesExpandedRow() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }

        // Initially expanded — collapse button visible
        composeTestRule.onNodeWithTag("tools_learn_collapse").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_row_learn").assertIsDisplayed()

        // Collapse
        composeTestRule.onNodeWithTag("tools_learn_collapse", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // Expanded row hidden, collapsed row shown
        composeTestRule.onNodeWithTag("tools_learn_collapsed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Getting started").assertIsDisplayed()
    }

    @Test
    fun toolsHub_learnCollapsedClickReExpands() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
            )
        }

        // Start expanded → collapse
        composeTestRule.onNodeWithTag("tools_learn_collapse", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_learn_collapsed").assertIsDisplayed()

        // Re-expand
        composeTestRule.onNodeWithTag("tools_learn_collapsed", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // Expanded row back
        composeTestRule.onNodeWithTag("tools_row_learn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_learn_collapse").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("tools_learn_collapsed", useUnmergedTree = true)
            .fetchSemanticsNodes().let { nodes ->
                assertTrue("Expected no collapsed row after re-expand", nodes.isEmpty())
            }
    }

    @Test
    fun toolsHub_learnCollapsedStateSearchStillWorks() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        // Collapse learn
        composeTestRule.onNodeWithTag("tools_learn_collapse", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // Search still works
        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("convert")
        composeTestRule.onNodeWithTag("tools_search_results_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_search_result_convert").assertIsDisplayed()
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
    @Test
    fun toolsHub_draftPrefill_showsPrefilledText() {
        composeTestRule.setContent {
            QuickActionSheetHarness(initialText = "Add milk to my shopping list")
        }

        composeTestRule.onNodeWithTag("quick_action_input")
            .assertIsDisplayed()
        // Verify the text field contains the prefilled text
        composeTestRule.onNodeWithText("Add milk to my shopping list")
            .assertIsDisplayed()
    }

    @Test
    fun toolsHub_draftPrefill_showsExampleHint() {
        composeTestRule.setContent {
            QuickActionSheetHarness(initialText = "Set a timer for 10 minutes")
        }

        composeTestRule.onNodeWithTag("quick_action_example_hint")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Set a timer for 10 minutes")
            .assertIsDisplayed()
    }

    @Test
    fun toolsHub_draftPrefill_hidesExampleHintWhenEmpty() {
        composeTestRule.setContent {
            QuickActionSheetHarness(initialText = "")
        }

        composeTestRule.onNodeWithTag("quick_action_input")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(
            "quick_action_example_hint", useUnmergedTree = true,
        ).fetchSemanticsNodes().let { nodes ->
            assertTrue(
                "Expected no example hint when initialText is empty",
                nodes.isEmpty(),
            )
        }
    }

    @Test
    fun toolsHub_draftPrefill_submitButtonShown() {
        composeTestRule.setContent {
            QuickActionSheetHarness(initialText = "Plan dinners for this week")
        }

        composeTestRule.onNodeWithTag("quick_action_submit_button")
            .assertIsDisplayed()
        // Submit button should be enabled when text is present
        composeTestRule.onNodeWithTag("quick_action_submit_button")
            .assertIsEnabled()
    }

    @Test
    fun toolsHub_draftPrefill_nothingAutoExecutes() {
        // Verify the draft route does NOT contain widgetQuery (which triggers auto-execute)
        val route = buildActionsDraftRoute("test prompt")
        assertEquals(false, route.contains("widgetQuery"))
        assertTrue(route.contains("draftQuery"))
        assertTrue(route.contains("openSheet=true"))
    }

    // ── Tools search tests ──────────────────────────────────────────────────

    @Test
    fun toolsSearch_fieldIsDisplayed() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_emptyQueryPreservesGroupedLayout() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        // Top-of-list items should be visible immediately
        composeTestRule.onNodeWithTag("tools_group_productivity").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_row_learn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_row_lists").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_row_notes").assertIsDisplayed()

        // Scroll to time_planning group (may be below fold on smaller screens)
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_group_time_planning"))
        composeTestRule.onNodeWithTag("tools_group_time_planning").assertIsDisplayed()

        // Scroll to and verify below-fold groups
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_group_people"))
        composeTestRule.onNodeWithTag("tools_group_people").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_group_utilities"))
        composeTestRule.onNodeWithTag("tools_group_utilities").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_group_personalisation"))
        composeTestRule.onNodeWithTag("tools_group_personalisation").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_group_app_setup"))
        composeTestRule.onNodeWithTag("tools_group_app_setup").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_clearButtonAppearsOnNonEmptyQuery() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("lists")
        composeTestRule.onNodeWithTag("tools_search_clear").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_byDestinationTitle() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("convert")
        composeTestRule.onNodeWithTag("tools_search_results_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_search_result_convert").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_byDestinationKeyword() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        // "shopping" keyword should match "Lists"
        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("shopping")
        composeTestRule.onNodeWithTag("tools_search_results_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_search_result_lists").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_byExamplePrompt() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("dentist")
        composeTestRule.onNodeWithTag("tools_search_examples_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_search_example_calendar_dentist").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_showsNoResultsForUnmatchedQuery() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("zzznotfound")
        composeTestRule.onNodeWithTag("tools_search_no_results").assertIsDisplayed()
        composeTestRule.onNodeWithText("No results found").assertIsDisplayed()

        // Group headers should NOT be visible when searching
        composeTestRule.onAllNodesWithTag("tools_group_productivity", useUnmergedTree = true)
            .fetchSemanticsNodes().let { nodes ->
                assertTrue("Expected no productivity group when no results", nodes.isEmpty())
            }
    }

    @Test
    fun toolsSearch_clearRestoresFullLayout() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("zzznotfound")
        composeTestRule.onNodeWithTag("tools_search_no_results").assertIsDisplayed()

        // Clear search
        composeTestRule.onNodeWithTag("tools_search_clear").performClick()
        composeTestRule.waitForIdle()

        // Full layout should be restored
        composeTestRule.onNodeWithTag("tools_group_productivity").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_row_lists").assertIsDisplayed()
    }

    @Test
    fun toolsSearch_matchedDestinationNavigates() {
        var lastRoute = ""
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = { lastRoute = it },
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("lists")
        composeTestRule.onNodeWithTag("tools_search_result_lists", useUnmergedTree = true).performClick()

        assertEquals(ROUTE_LISTS, lastRoute)
    }

    @Test
    fun toolsSearch_matchedExampleOpensPrompt() {
        var capturedPrompt = ""
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
                onOpenPrompt = { capturedPrompt = it },
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("dentist")
        composeTestRule.onNodeWithTag("tools_search_example_calendar_dentist", useUnmergedTree = true).performClick()

        assertTrue("Expected 'dentist' in captured prompt, got: $capturedPrompt",
            capturedPrompt.contains("dentist", ignoreCase = true))
    }

    @Test
    fun toolsSearch_showsBothDestinationsAndExamples() {
        composeTestRule.setContent {
            ToolsHubScreen(
                onOpenDrawer = {},
                onNavigateToRoute = {},
                onNavigateToSettings = {},
            )
        }

        composeTestRule.onNodeWithTag("tools_search_field").performTextInput("convert")

        // Should show both "Tools" and "Examples" groups
        composeTestRule.onNodeWithTag("tools_search_results_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_search_examples_header").assertIsDisplayed()

        // Convert destination should be matched
        composeTestRule.onNodeWithTag("tools_search_result_convert").assertIsDisplayed()

        // Conversion examples should be matched
        composeTestRule.onNodeWithTag("tools_search_example_convert_cups_ml").assertIsDisplayed()
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

    @Test
    fun bottomNav_toolsFromActionsDraftRoute_navigatesToTools() {
        composeTestRule.setContent {
            NavigationHarness()
        }

        // Step 1: Start on Tools hub
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Step 2: Navigate to Learn, then to Actions via draft link
        composeTestRule.onNodeWithTag("btn_draft_navigate").performClick()
        composeTestRule.waitForIdle()

        // Verify Actions screen is displayed
        composeTestRule.onNodeWithTag("actions_screen").assertIsDisplayed()

        // Step 3: Tap Tools bottom nav
        composeTestRule.onNodeWithTag("bottom_nav_tools").performClick()
        composeTestRule.waitForIdle()

        // Step 4: Verify we're back on Tools hub
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
    }

    @Test
    fun bottomNav_toolsAfterChatFromActionsDraft_restoresToolsNotActions() {
        composeTestRule.setContent {
            NavigationHarness()
        }

        // Step 1: Start on Tools hub
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Step 2: Navigate to Learn, then to Actions via draft link
        composeTestRule.onNodeWithTag("btn_nav_learn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_draft_navigate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("actions_screen").assertIsDisplayed()

        // Step 3: Switch to Chat
        composeTestRule.onNodeWithTag("bottom_nav_chat").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_screen").assertIsDisplayed()

        // Step 4: Switch to Tools
        composeTestRule.onNodeWithTag("bottom_nav_tools").performClick()
        composeTestRule.waitForIdle()

        // Step 5: Verify we're on the Tools hub, not Actions
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(
            "actions_screen", useUnmergedTree = true,
        ).fetchSemanticsNodes().let { nodes ->
            assertTrue(
                "Expected no Actions screen after navigating Tools from Chat",
                nodes.isEmpty(),
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionSheetHarness(initialText: String) {
    var showSheet by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf(initialText) }
    // When initialText changes externally, replace the text field value
    LaunchedEffect(initialText) {
        if (initialText.isNotBlank()) {
            inputText = initialText
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "Quick Action",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type a command or tap the mic for a voice action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (initialText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Example loaded — review or edit before running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("quick_action_example_hint"),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("What do you want to do?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("quick_action_input"),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.testTag("quick_action_submit_button"),
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send",
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Minimal NavHost harness simulating bottom-nav flows:
 * Tools → Learn → Actions draft → [Chat] → Tools
 * Uses simple navigate+launchSingleTop for bottom-nav items.
 */
@Composable
private fun NavigationHarness() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentBaseRoute = currentRoute?.substringBefore('?')

    Column {
        NavHost(
            navController = navController,
            startDestination = "tools",
            modifier = Modifier.weight(1f),
        ) {
            composable("tools") {
                Column {
                    Text("Tools Hub", modifier = Modifier.testTag("tools_screen"))
                    Button(
                        onClick = { navController.navigate("tools/learn") { launchSingleTop = true } },
                        modifier = Modifier.testTag("btn_nav_learn"),
                    ) {
                        Text("Learn what Jandal can do")
                    }
                    Button(
                        onClick = {
                            navController.navigate(buildActionsDraftRoute("Convert 2 cups to mL")) {
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.testTag("btn_draft_navigate"),
                    ) {
                        Text("Tap example (direct)")
                    }
                }
            }

            composable("chat") {
                Text("Chat Screen", modifier = Modifier.testTag("chat_screen"))
            }

            composable("tools/learn") {
                Column {
                    Text("Learn Screen")
                    Button(
                        onClick = {
                            navController.navigate(buildActionsDraftRoute("Convert 2 cups to mL")) {
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.testTag("btn_draft_navigate"),
                    ) {
                        Text("Tap example")
                    }
                }
            }

            composable(
                route = "actions?openSheet={openSheet}&startVoice={startVoice}&widgetQuery={widgetQuery}&widgetVoice={widgetVoice}&draftQuery={draftQuery}",
                arguments = listOf(
                    navArgument("openSheet") { type = NavType.BoolType; defaultValue = false },
                    navArgument("startVoice") { type = NavType.BoolType; defaultValue = false },
                    navArgument("widgetQuery") { type = NavType.StringType; defaultValue = "" },
                    navArgument("widgetVoice") { type = NavType.BoolType; defaultValue = false },
                    navArgument("draftQuery") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                Text("Actions Screen", modifier = Modifier.testTag("actions_screen"))
                Text("Draft: ${it.arguments?.getString("draftQuery")}")
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = currentBaseRoute == "chat",
                onClick = {
                    navController.navigate("chat") { launchSingleTop = true }
                },
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                label = { Text("Chat") },
                modifier = Modifier.testTag("bottom_nav_chat"),
            )
            NavigationBarItem(
                selected = currentBaseRoute == "tools",
                onClick = {
                    navController.navigate("tools") { launchSingleTop = true }
                },
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                label = { Text("Tools") },
                modifier = Modifier.testTag("bottom_nav_tools"),
            )
        }
    }
}