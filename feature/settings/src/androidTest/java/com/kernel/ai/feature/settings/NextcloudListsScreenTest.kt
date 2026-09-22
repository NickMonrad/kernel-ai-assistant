package com.kernel.ai.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextInput
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.nextcloud.NextcloudAccount
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import com.kernel.ai.core.memory.nextcloud.NextcloudListState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val TASKS_HREF = "https://cloud.example/remote.php/dav/calendars/alice/tasks/"

/**
 * The user-visible contract of the Nextcloud lists screen (#1551): connected-account presentation,
 * the three mutually exclusive sections, search and state filtering, and per-list state actions.
 * Driven by state and fakes only; no network.
 */
class NextcloudListsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun connectedAccountShowsSummaryAndNoCredentialFields() {
        show(state = connectedState())

        composeTestRule.onNodeWithTag("nextcloud_account_summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected to https://cloud.example").assertIsDisplayed()
        composeTestRule.onNodeWithText("as alice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_address_field").assertDoesNotExist()
        composeTestRule.onNodeWithTag("nextcloud_app_password_field").assertDoesNotExist()
    }

    @Test
    fun initialSetupUsesHttpsByDefaultWithInsecureHttpOff() {
        show(state = NextcloudSettingsState())

        composeTestRule.onNodeWithTag("nextcloud_address_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_insecure_http_toggle").assertIsOff()
        composeTestRule.onNodeWithText("HTTPS is used unless you enable insecure HTTP.").assertIsDisplayed()
        composeTestRule.onNodeWithText(INSECURE_HTTP_WARNING).assertDoesNotExist()
    }

    @Test
    fun selectingInsecureHttpShowsTheWarning() {
        show(state = NextcloudSettingsState(allowInsecureHttp = true))

        composeTestRule.onNodeWithTag("nextcloud_insecure_http_toggle").assertIsOn()
        composeTestRule.onNodeWithText(INSECURE_HTTP_WARNING).assertIsDisplayed()
    }

    @Test
    fun aRejectedHttpAddressIsReportedInline() {
        show(
            state = NextcloudSettingsState(
                address = "http://cloud.example",
                addressError = HTTP_REJECTION,
            ),
        )

        composeTestRule.onNodeWithText(HTTP_REJECTION).assertIsDisplayed()
    }

    @Test
    fun expiredAuthenticationOffersReconnectWithoutCredentialFields() {
        show(state = connectedState().copy(authenticationFailed = true))

        composeTestRule.onNodeWithText("Nextcloud connection needs attention").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Jandal lists are still available on this device.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_reconnect").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_address_field").assertDoesNotExist()
    }

    @Test
    fun theThreeSectionsRenderWithoutDuplicatingAList() {
        show(state = connectedState(), sections = mixedSections())

        composeTestRule.onNodeWithText("CONNECTED LISTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("NEXTCLOUD ONLY").assertIsDisplayed()
        composeTestRule.onNodeWithText("JANDAL ONLY").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Shopping").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Work tasks").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Holiday packing").assertCountEquals(1)
    }

    @Test
    fun connectedRowsExposeSyncStateAndRecovery() {
        show(
            state = connectedState(),
            sections = NextcloudListSections(
                connected = listOf(
                    item("Shopping", NextcloudListState.UP_TO_DATE, "collection-shopping"),
                    item("Family chores", NextcloudListState.SYNC_OFF, "collection-chores"),
                    item("Hardware store", NextcloudListState.NEEDS_ATTENTION, "collection-hardware"),
                ),
            ),
        )

        // ListItem merges its descendants, so the row carries both the visible label and the
        // accessible state description; the same words also label the state filter chips.
        composeTestRule.onNodeWithTag("nextcloud_row_collection:collection-shopping")
            .assertTextContains("Up to date")
            .assertContentDescriptionContains("Nextcloud sync up to date")
        composeTestRule.onNodeWithTag("nextcloud_row_collection:collection-chores")
            .assertTextContains("Sync off")
            .assertContentDescriptionContains("Nextcloud sync stopped")
        composeTestRule.onNodeWithTag("nextcloud_row_collection:collection-hardware")
            .assertTextContains("Needs attention")
            .assertContentDescriptionContains("Nextcloud sync needs attention")
        composeTestRule.onNodeWithTag("nextcloud_retry_collection:collection-hardware").assertIsDisplayed()
    }

    @Test
    fun aConnectedRowStopsAndResumesThroughTheSameAssociation() {
        var stopped: NextcloudListItem? = null
        var resumed: NextcloudListItem? = null
        show(
            state = connectedState(),
            sections = NextcloudListSections(
                connected = listOf(
                    item("Shopping", NextcloudListState.UP_TO_DATE, "collection-shopping"),
                    item("Family chores", NextcloudListState.SYNC_OFF, "collection-chores"),
                ),
            ),
            onStopSync = { stopped = it },
            onResumeSync = { resumed = it },
        )

        composeTestRule.onNodeWithTag("nextcloud_overflow_collection:collection-shopping").performClick()
        composeTestRule.onNodeWithText("Stop Nextcloud sync").performClick()
        composeTestRule.onNodeWithText("Stop syncing \u201CShopping\u201D with Nextcloud?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nextcloud_stop_confirm").performClick()
        assertEquals("collection-shopping", stopped?.collectionId)

        composeTestRule.onNodeWithTag("nextcloud_overflow_collection:collection-chores").performClick()
        composeTestRule.onNodeWithText("Resume Nextcloud sync").performClick()
        assertEquals("collection-chores", resumed?.collectionId)
    }

    @Test
    fun unboundRowsOfferOneClearActionEach() {
        var added: NextcloudListItem? = null
        var synced: NextcloudListItem? = null
        show(
            state = connectedState(),
            sections = mixedSections(),
            onAddToJandal = { added = it },
            onSyncWithNextcloud = { synced = it },
        )

        composeTestRule.onNodeWithText("Add to Jandal").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Sync with Nextcloud").assertIsDisplayed().performClick()

        assertEquals("Work tasks", added?.displayName)
        assertEquals("Holiday packing", synced?.displayName)
    }

    @Test
    fun aRemoteOnlyListNeverDisplaysItsInternalHref() {
        show(state = connectedState(), sections = mixedSections())

        composeTestRule.onNodeWithText("Shopping").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(TASKS_HREF, substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("collection-shopping", substring = true).assertCountEquals(0)
    }

    @Test
    fun searchFiltersByDisplayedListNameAcrossSections() {
        showInteractive(sections = mixedSections())

        composeTestRule.onNodeWithTag("nextcloud_search_field").performTextInput("holiday")

        composeTestRule.onNodeWithText("JANDAL ONLY").assertIsDisplayed()
        composeTestRule.onNodeWithText("Holiday packing").assertIsDisplayed()
        composeTestRule.onNodeWithText("CONNECTED LISTS").assertDoesNotExist()
        composeTestRule.onNodeWithText("NEXTCLOUD ONLY").assertDoesNotExist()
    }

    @Test
    fun stateFiltersExposeTheCorrectRowsAndComposeWithSearch() {
        showInteractive(sections = mixedSections())

        composeTestRule.onNodeWithTag("nextcloud_filter_${NextcloudListFilter.NEXTCLOUD_ONLY.name}").performClick()
        composeTestRule.onNodeWithText("Work tasks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shopping").assertDoesNotExist()
        composeTestRule.onNodeWithText("Holiday packing").assertDoesNotExist()

        composeTestRule.onNodeWithTag("nextcloud_filter_${NextcloudListFilter.ALL.name}").performClick()
        composeTestRule.onNodeWithTag("nextcloud_search_field").performTextInput("shop")
        composeTestRule.onNodeWithText("Shopping").assertIsDisplayed()
        composeTestRule.onNodeWithText("Work tasks").assertDoesNotExist()

        composeTestRule.onNodeWithTag("nextcloud_filter_${NextcloudListFilter.JANDAL_ONLY.name}").performClick()
        composeTestRule.onNodeWithText("Shopping").assertDoesNotExist()
        composeTestRule.onNodeWithText("No lists match \"shop\".").assertIsDisplayed()
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private fun show(
        state: NextcloudSettingsState,
        sections: NextcloudListSections = NextcloudListSections.EMPTY,
        onStopSync: (NextcloudListItem) -> Unit = {},
        onResumeSync: (NextcloudListItem) -> Unit = {},
        onAddToJandal: (NextcloudListItem) -> Unit = {},
        onSyncWithNextcloud: (NextcloudListItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            NextcloudListsContent(
                state = state,
                sections = sections,
                snackbarHostState = remember { SnackbarHostState() },
                onStopSync = onStopSync,
                onResumeSync = onResumeSync,
                onAddToJandal = onAddToJandal,
                onSyncWithNextcloud = onSyncWithNextcloud,
            )
        }
    }

    /** Wires the real search/filter functions to the screen controls, as the ViewModel does. */
    private fun showInteractive(sections: NextcloudListSections) {
        composeTestRule.setContent {
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf(NextcloudListFilter.ALL) }
            NextcloudListsContent(
                state = connectedState().copy(query = query, filter = filter),
                sections = sections.filtered(query, filter),
                snackbarHostState = remember { SnackbarHostState() },
                onQueryChange = { query = it },
                onFilterChange = { filter = it },
            )
        }
    }

    private fun connectedState() = NextcloudSettingsState(
        connected = NextcloudAccount("https://cloud.example", "alice"),
        discovered = true,
    )

    private fun mixedSections() = NextcloudListSections(
        connected = listOf(item("Shopping", NextcloudListState.UP_TO_DATE, "collection-shopping")),
        nextcloudOnly = listOf(
            NextcloudListItem(
                displayName = "Work tasks",
                section = NextcloudListSection.NEXTCLOUD_ONLY,
                remoteHref = TASKS_HREF,
            ),
        ),
        jandalOnly = listOf(
            NextcloudListItem(
                displayName = "Holiday packing",
                section = NextcloudListSection.JANDAL_ONLY,
                listId = 9L,
                collectionId = null,
            ),
        ),
    )

    private fun item(name: String, state: NextcloudListState, collectionId: String) = NextcloudListItem(
        displayName = name,
        section = NextcloudListSection.CONNECTED,
        listId = name.hashCode().toLong(),
        collectionId = collectionId,
        syncState = state,
    )

    private companion object {
        const val INSECURE_HTTP_WARNING =
            "Credentials and list traffic will not be encrypted. Use this only when the server cannot use HTTPS."
        const val HTTP_REJECTION =
            "Plain HTTP is disabled. Enter an https:// address, or enable \"Use insecure HTTP\"."
    }
}
