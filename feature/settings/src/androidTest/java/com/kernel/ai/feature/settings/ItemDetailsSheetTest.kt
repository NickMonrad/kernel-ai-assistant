package com.kernel.ai.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kernel.ai.core.memory.entity.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ItemDetailsSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun topLevelCreateUsesDetailsSurfaceAndReturnsEnteredFields() {
        var saved: ListItemEntity? = null
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = ListItemEntity(listId = 1L, text = ""),
                    title = "Add item",
                    primaryActionLabel = "Add",
                    canAddSubItem = false,
                    onAddSubItem = {},
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("list_item_details_save").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("Top-level task")
        composeTestRule.onNodeWithTag("list_item_details_description")
            .performTextInput("Notes\nhttps://example.com/task")
        composeTestRule.onNodeWithTag("list_item_details_favourite_switch").performClick()
        composeTestRule.onNodeWithTag("list_item_details_save").performClick()

        assertEquals("Top-level task", saved?.text)
        assertEquals("Notes\nhttps://example.com/task", saved?.description)
        assertEquals(true, saved?.isFavourite)
        assertNull(saved?.dueAt)
        assertNull(saved?.notificationTime)
    }

    @Test
    fun childCreateUsesSubItemActionLabel() {
        var saved: ListItemEntity? = null
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = ListItemEntity(listId = 1L, text = ""),
                    title = "Add sub-item",
                    primaryActionLabel = "Add",
                    canAddSubItem = false,
                    onAddSubItem = {},
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add sub-item").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add").assertIsDisplayed()
        composeTestRule.onNodeWithTag("list_item_details_title").performTextInput("Child task")
        composeTestRule.onNodeWithTag("list_item_details_save").performClick()

        assertEquals("Child task", saved?.text)
    }

    @Test
    fun cancelAndBlankTitleDoNotCreate() {
        var saved: ListItemEntity? = null
        var dismissed = false
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = ListItemEntity(listId = 1L, text = ""),
                    title = "Add item",
                    primaryActionLabel = "Add",
                    canAddSubItem = false,
                    onAddSubItem = {},
                    onSave = { saved = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("list_item_details_save").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("list_item_details_cancel").performClick()

        assertNull(saved)
        assertEquals(true, dismissed)
    }

    @Test
    fun editModeRetainsSavedDetails() {
        var saved: ListItemEntity? = null
        composeTestRule.setContent {
            MaterialTheme {
                ItemDetailsSheet(
                    item = ListItemEntity(
                        id = 7L,
                        listId = 1L,
                        text = "Existing item",
                        description = "Existing notes",
                        dueAt = 1_900_000_000_000L,
                        isFavourite = true,
                        notificationTime = 1_899_999_940_000L,
                    ),
                    canAddSubItem = true,
                    onAddSubItem = {},
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Edit item").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add sub-item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("list_item_details_save").performClick()

        assertEquals("Existing item", saved?.text)
        assertEquals("Existing notes", saved?.description)
        assertEquals(1_900_000_000_000L, saved?.dueAt)
        assertEquals(true, saved?.isFavourite)
        assertEquals(1_899_999_940_000L, saved?.notificationTime)
    }
}
