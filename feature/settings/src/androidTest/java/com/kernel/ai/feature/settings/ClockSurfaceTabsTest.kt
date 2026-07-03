package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ClockSurfaceTabsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clockTabsShowAllFourSurfacesWithoutScrolling() {
        composeTestRule.setContent {
            var selectedTab by remember { mutableStateOf(ClockSurfaceTab.TIMERS) }
            Column {
                ClockSurfaceTabs(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                )
                Text("Selected: ${selectedTab.name}")
            }
        }
        composeTestRule.onNodeWithTag("clock_surface_tabs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Timers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alarms").assertIsDisplayed()
        composeTestRule.onNodeWithText("World Clock").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stopwatch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Selected: TIMERS").assertIsDisplayed()

        // Tap Stopwatch and verify selected state updates
        composeTestRule.onNodeWithText("Stopwatch").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Selected: STOPWATCH").assertIsDisplayed()
    }
}
