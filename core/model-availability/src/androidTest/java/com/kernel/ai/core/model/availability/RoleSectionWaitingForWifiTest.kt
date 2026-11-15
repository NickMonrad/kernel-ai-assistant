package com.kernel.ai.core.model.availability

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose tests for the "Waiting for Wi-Fi" state on a queued model row.
 *
 * A queued Wi-Fi-only download is a Preparing state that is not auto-queued. With
 * `waitingForWifi` set, the row must read "Waiting" and offer the mobile-data shortcut.
 */
class RoleSectionWaitingForWifiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val model = KernelModel.GEMMA_4_E2B
    private val shortcutDescription = "${ModelRoleCopy.MOBILE_DATA_SHORTCUT} ${model.displayName}"

    private fun section() = ModelRoleSection(
        role = ModelRole.CHAT_BRAIN,
        status = RoleStatus.DOWNLOADING,
        using = null,
        preferred = null,
        rows = listOf(
            ModelRoleRow(
                model = model,
                state = DownloadState.NotDownloaded,
                group = ModelGroup.IN_USE,
                inUse = false,
                required = true,
                recommended = false,
                selectable = false,
                allowedOnPhone = true,
            ),
        ),
    )

    @Test
    fun queuedWifiOnlyRowShowsWaitingAndMobileDataShortcut() {
        var shortcutTaps = 0
        composeRule.setContent {
            MaterialTheme {
                RoleSectionView(
                    section = section(),
                    availabilityOf = { ModelAvailabilityState.Preparing(progress = 0f, isAutoQueued = false) },
                    onSelect = null,
                    onAutoSelect = null,
                    onPrimary = {},
                    canDelete = { false },
                    onDelete = {},
                    waitingForWifi = setOf(model),
                    onMobileDataShortcut = { shortcutTaps++ },
                )
            }
        }

        composeRule.onNodeWithText(ModelRoleCopy.WAITING_FOR_WIFI).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
        composeRule.onAllNodesWithText("Downloading").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(shortcutDescription).assertIsDisplayed().performClick()

        assertEquals(1, shortcutTaps)
    }

    @Test
    fun queuedRowNotWaitingForWifiHasNoWaitingTextOrShortcut() {
        composeRule.setContent {
            MaterialTheme {
                RoleSectionView(
                    section = section(),
                    availabilityOf = { ModelAvailabilityState.Preparing(progress = 0f, isAutoQueued = false) },
                    onSelect = null,
                    onAutoSelect = null,
                    onPrimary = {},
                    canDelete = { false },
                    onDelete = {},
                    waitingForWifi = emptySet(),
                    onMobileDataShortcut = {},
                )
            }
        }

        composeRule.onAllNodesWithText(ModelRoleCopy.WAITING_FOR_WIFI).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(shortcutDescription).assertCountEquals(0)
        composeRule.onNodeWithText("Downloading").assertIsDisplayed()
    }
}
