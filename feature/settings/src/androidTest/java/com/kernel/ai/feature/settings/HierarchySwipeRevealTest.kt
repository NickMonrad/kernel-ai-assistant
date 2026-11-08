package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * The hierarchy swipe reveal must name the depth change, so it cannot read as the archive/dismiss
 * gesture.
 */
class HierarchySwipeRevealTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rightwardRevealNamesMakeSubItem() {
        composeTestRule.setContent { HierarchySwipeReveal(indenting = true) }

        composeTestRule.onNodeWithTag("hierarchy_make_sub_item_reveal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Make sub-item").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move to top level").assertDoesNotExist()
    }

    @Test
    fun leftwardRevealNamesMoveToTopLevel() {
        composeTestRule.setContent { HierarchySwipeReveal(indenting = false) }

        composeTestRule.onNodeWithTag("hierarchy_move_to_top_level_reveal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move to top level").assertIsDisplayed()
        composeTestRule.onNodeWithText("Make sub-item").assertDoesNotExist()
    }
}
