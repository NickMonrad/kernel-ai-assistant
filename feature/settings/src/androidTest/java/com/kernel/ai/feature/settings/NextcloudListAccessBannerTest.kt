package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The list-detail banner for a shared Nextcloud list that can no longer be written (#1548): it must
 * explain the state and offer exactly the resolution the user needs.
 */
class NextcloudListAccessBannerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun strandedWorkOffersKeepOrDiscard() {
        var kept = 0
        var discarded = 0
        composeTestRule.setContent {
            NextcloudAccessBanner(
                unsyncedChanges = true,
                available = true,
                onKeepLocalCopy = { kept += 1 },
                onDiscardLocalChanges = { discarded += 1 },
                onSaveLocalCopy = {},
            )
        }

        composeTestRule.onNodeWithText("This list became read-only in Nextcloud. Your local changes are not synced.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_list_keep_local_copy").performClick()
        composeTestRule.onNodeWithTag("nextcloud_list_discard_local_changes").performClick()
        assertEquals(1, kept)
        assertEquals(1, discarded)
        composeTestRule.onNodeWithTag("nextcloud_list_save_local_copy").assertDoesNotExist()
    }

    @Test
    fun removedAccessWithStrandedWorkExplainsTheLossBeforeCleanup() {
        composeTestRule.setContent {
            NextcloudAccessBanner(
                unsyncedChanges = true,
                available = false,
                onKeepLocalCopy = {},
                onDiscardLocalChanges = {},
                onSaveLocalCopy = {},
            )
        }

        composeTestRule.onNodeWithText("Nextcloud access to this list was removed. Your local changes are not synced.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_list_keep_local_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_list_discard_local_changes").assertIsDisplayed()
    }

    @Test
    fun removedAccessWithoutStrandedWorkOffersALocalCopyOnly() {
        var saved = 0
        composeTestRule.setContent {
            NextcloudAccessBanner(
                unsyncedChanges = false,
                available = false,
                onKeepLocalCopy = {},
                onDiscardLocalChanges = {},
                onSaveLocalCopy = { saved += 1 },
            )
        }

        composeTestRule.onNodeWithText("Nextcloud access to this list was removed.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_list_save_local_copy").performClick()
        assertEquals(1, saved)
        composeTestRule.onNodeWithTag("nextcloud_list_discard_local_changes").assertDoesNotExist()
    }

    @Test
    fun readOnlyShareAllowsCopyingTheListOutProactively() {
        var saved = 0
        composeTestRule.setContent {
            NextcloudAccessBanner(
                unsyncedChanges = false,
                available = true,
                onKeepLocalCopy = {},
                onDiscardLocalChanges = {},
                onSaveLocalCopy = { saved += 1 },
            )
        }

        composeTestRule.onNodeWithText("This Nextcloud list is read-only. Changes are unavailable.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_list_save_local_copy").performClick()
        assertEquals(1, saved)
    }
}
