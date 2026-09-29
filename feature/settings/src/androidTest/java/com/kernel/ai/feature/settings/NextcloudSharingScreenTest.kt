package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.memory.nextcloud.NextcloudShare
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission
import com.kernel.ai.core.memory.nextcloud.NextcloudSharee
import com.kernel.ai.core.memory.nextcloud.NextcloudShareeType
import org.junit.Assert.assertEquals
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
        composeTestRule.onNodeWithTag("nextcloud_sharing_search").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun writableCollectionSearchesAndOffersBothPermissionLevels() {
        var granted: Pair<String, NextcloudSharePermission>? = null
        val sharee = NextcloudSharee(
            principal = "principal:principals/users/bob",
            displayName = "Bob",
            type = NextcloudShareeType.USER,
        )
        composeTestRule.setContent {
            NextcloudSharingContent(
                state = NextcloudSharingState(
                    loading = false,
                    writable = true,
                    query = "bob",
                    results = listOf(sharee),
                ),
                onGrant = { target, permission -> granted = target.principal to permission },
            )
        }

        composeTestRule.onNodeWithTag("nextcloud_sharing_search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_sharing_grant_read").performClick()
        assertEquals(sharee.principal to NextcloudSharePermission.READ_ONLY, granted)
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
