package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HierarchyDisclosureAndDropTargetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collapsedParentDisclosureExposesExpandActionAndTogglesItsAccessibleState() {
        val expanded = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                HierarchyDisclosureButton(
                    expanded = expanded.value,
                    onClick = { expanded.value = !expanded.value },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand sub-items").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Collapse sub-items").assertIsDisplayed()
        assertEquals(true, expanded.value)
    }

    @Test
    fun activeChildInsertionTargetNamesItsHierarchyOutcome() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    HierarchyDropIndicator(
                        target = HierarchyDropTarget.ChildInsertion(parentRowId = 10L, beforeChildRowId = 11L),
                        isChild = true,
                        isActive = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Insert as sub-item").assertIsDisplayed()
    }

    @Test
    fun directParentRowTargetNamesTheLastChildOutcome() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    HierarchyDropIndicator(
                        target = HierarchyDropTarget.ParentRow(parentRowId = 10L),
                        isChild = true,
                        isActive = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Append as last sub-item").assertIsDisplayed()
    }
}
