package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class ClockSurfaceTabsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clockTabsAreHorizontallyScrollableAndReachStopwatch() {
        composeTestRule.setContent {
            var selectedTab by mutableStateOf(ClockSurfaceTab.TIMERS)
            Column {
                ClockSurfaceTabs(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                )
                Text("Selected: ${selectedTab.name}")
            }
        }

        composeTestRule.onNodeWithTag("clock_surface_tabs").assert(hasScrollAction())
        composeTestRule.onNodeWithTag("clock_surface_tabs").performScrollToNode(hasText("Stopwatch"))
        composeTestRule.onNodeWithText("Stopwatch").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Selected: STOPWATCH").assertIsDisplayed()
    }
}
