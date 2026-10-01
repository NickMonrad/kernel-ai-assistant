package com.kernel.ai.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.memory.nextcloud.NextcloudShare
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission
import com.kernel.ai.core.memory.nextcloud.NextcloudSharee
import com.kernel.ai.core.memory.nextcloud.NextcloudShareeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class NextcloudSharingScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun readOnlyCollectionExplainsWhySharingControlsAreUnavailable() {
        composeTestRule.setContent {
            NextcloudSharingContent(
                state = NextcloudSharingState(
                    loading = false,
                    writable = false,
                    shares = listOf(share()),
                ),
            )
        }

        composeTestRule.onNodeWithTag("nextcloud_sharing_read_only").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("nextcloud_sharing_search").assertCountEquals(0)
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun writableCollectionSelectsPermissionAndSharesOnlyAfterExplicitAction() {
        val sharee = NextcloudSharee(
            principal = "principal:principals/users/bob",
            displayName = "Bob",
            type = NextcloudShareeType.USER,
        )
        val uiState = mutableStateOf(
            NextcloudSharingState(
                loading = false,
                writable = true,
                query = "bob",
                results = listOf(sharee),
            ),
        )
        var granted: Pair<String, NextcloudSharePermission>? = null

        composeTestRule.setContent {
            NextcloudSharingContent(
                state = uiState.value,
                onSelectSharee = { target ->
                    uiState.value = uiState.value.copy(selectedSharee = target)
                },
                onSelectPermission = { permission ->
                    uiState.value = uiState.value.copy(selectedPermission = permission)
                },
                onShareSelected = {
                    val selected = uiState.value.selectedSharee
                    if (selected != null) {
                        granted = selected.principal to uiState.value.selectedPermission
                    }
                },
            )
        }

        composeTestRule.onNodeWithTag("nextcloud_sharing_result").performClick()
        assertNull(granted)
        composeTestRule.onNodeWithTag("nextcloud_sharing_selected").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_sharing_permission_edit").performClick()
        assertNull(granted)
        composeTestRule.onNodeWithTag("nextcloud_sharing_share").performClick()

        assertEquals(sharee.principal to NextcloudSharePermission.EDITABLE, granted)
    }

    @Test
    fun writableCollectionExposesPermissionChangeAndRemoval() {
        var changed: NextcloudSharePermission? = null
        var removed: String? = null
        composeTestRule.setContent {
            NextcloudSharingContent(
                state = NextcloudSharingState(
                    loading = false,
                    writable = true,
                    shares = listOf(share(NextcloudSharePermission.READ_ONLY)),
                ),
                onChangePermission = { _, permission -> changed = permission },
                onRemove = { removed = it.principal },
            )
        }

        composeTestRule.onNodeWithTag("nextcloud_sharing_permission").performClick()
        composeTestRule.onNodeWithTag("nextcloud_sharing_remove").performClick()

        assertEquals(NextcloudSharePermission.EDITABLE, changed)
        assertEquals("principal:principals/users/bob", removed)
    }

    private fun share(permission: NextcloudSharePermission = NextcloudSharePermission.EDITABLE) =
        NextcloudShare(
            principal = "principal:principals/users/bob",
            displayName = "Bob",
            type = NextcloudShareeType.USER,
            permission = permission,
            invitationAccepted = true,
        )
}
