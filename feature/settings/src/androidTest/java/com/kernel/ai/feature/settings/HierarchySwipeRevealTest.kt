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
    fun swipeRightRevealNamesIndent() {
        composeTestRule.setContent { HierarchySwipeReveal(indenting = true) }

        composeTestRule.onNodeWithTag("hierarchy_indent_reveal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Indent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outdent").assertDoesNotExist()
    }

    @Test
    fun swipeLeftRevealNamesOutdent() {
        composeTestRule.setContent { HierarchySwipeReveal(indenting = false) }

        composeTestRule.onNodeWithTag("hierarchy_outdent_reveal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outdent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Indent").assertDoesNotExist()
    }
}
