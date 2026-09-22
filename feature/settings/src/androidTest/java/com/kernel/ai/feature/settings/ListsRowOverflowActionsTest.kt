package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.memory.nextcloud.NextcloudListState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Per-list Nextcloud lifecycle from the Lists row overflow (#1551): the same action set must be
 * reachable whether or not an account exists yet, and no surface may offer two Nextcloud actions.
 */
class ListsRowOverflowActionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun anUnconnectedListOffersSyncWithNextcloudWithoutAnAccount() {
        var synced = false
        showMenu(NextcloudRowActions(state = null, onSyncWithNextcloud = { synced = true }))

        composeTestRule.onNodeWithText("Sync with Nextcloud").assertIsDisplayed().performClick()

        assertEquals(true, synced)
        composeTestRule.onNodeWithText("Stop Nextcloud sync").assertDoesNotExist()
        composeTestRule.onNodeWithText("Resume Nextcloud sync").assertDoesNotExist()
    }

    @Test
    fun aSyncingListOffersStopNextcloudSync() {
        var stopped = false
        showMenu(
            NextcloudRowActions(
                state = NextcloudListState.UP_TO_DATE,
                onStopSync = { stopped = true },
            ),
        )

        composeTestRule.onNodeWithText("Stop Nextcloud sync").assertIsDisplayed().performClick()

        assertEquals(true, stopped)
        composeTestRule.onNodeWithText("Sync with Nextcloud").assertDoesNotExist()
    }

    @Test
    fun aListWithSyncStoppedOffersResumeNextcloudSync() {
        var resumed = false
        showMenu(
            NextcloudRowActions(
                state = NextcloudListState.SYNC_OFF,
                onResumeSync = { resumed = true },
            ),
        )

        composeTestRule.onNodeWithText("Resume Nextcloud sync").assertIsDisplayed().performClick()

        assertEquals(true, resumed)
        composeTestRule.onNodeWithText("Stop Nextcloud sync").assertDoesNotExist()
    }

    @Test
    fun aConnectedListOpensItsExistingNextcloudState() {
        var opened = false
        showMenu(
            NextcloudRowActions(
                state = NextcloudListState.NEEDS_ATTENTION,
                onOpenNextcloud = { opened = true },
            ),
        )

        composeTestRule.onNodeWithText("Nextcloud").assertIsDisplayed().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun everyRowExposesTheSameShareAndExportActions() {
        var exported = false
        showMenu(NextcloudRowActions(state = null), onExport = { exported = true })

        composeTestRule.onNodeWithText("Share as text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy to clipboard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export Jandal file").assertIsDisplayed().performClick()

        assertEquals(true, exported)
        composeTestRule.onNodeWithText("Export encrypted package").assertDoesNotExist()
    }

    @Test
    fun aLocalOnlyListShowsNoNextcloudIndicator() {
        composeTestRule.setContent {
            Column {
                NextcloudListIndicator(null)
                NextcloudListIndicator(NextcloudListState.SYNC_OFF)
            }
        }

        composeTestRule.onAllNodesWithTag("lists_nextcloud_indicator").assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Nextcloud sync stopped").assertIsDisplayed()
    }

    private fun showMenu(
        nextcloud: NextcloudRowActions,
        onExport: () -> Unit = {},
    ) {
        val expanded = mutableStateOf(true)
        composeTestRule.setContent {
            ListRowOverflowMenu(
                expanded = expanded.value,
                isArchivedView = false,
                nextcloud = nextcloud,
                onRename = {},
                onArchive = {},
                onRestore = {},
                onShare = {},
                onCopy = {},
                onExport = onExport,
                onDelete = {},
                onDismiss = { expanded.value = false },
            )
        }
    }
}
