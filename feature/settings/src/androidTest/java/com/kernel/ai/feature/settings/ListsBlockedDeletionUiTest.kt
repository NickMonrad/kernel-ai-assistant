package com.kernel.ai.feature.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.repository.ListMutationBlockedException
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercises the real ListsScreen and ListsViewModel against disposable, in-memory guarded lists. */
class ListsBlockedDeletionUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: KernelDatabase
    private lateinit var mutations: ListMutationRepository
    private lateinit var scheduler: ListNotificationScheduler
    private lateinit var nextcloud: NextcloudSyncAdapter
    private lateinit var viewModel: ListsViewModel
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var fixtures: List<BlockedListFixture>

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mutations = ListMutationRepository(
            database = database,
            listItemDao = database.listItemDao(),
            listNameDao = database.listNameDao(),
            actorDao = database.listActorStateDao(),
            appliedDao = database.listAppliedChangeDao(),
            changeDao = database.listChangeDao(),
            sourceDao = database.listSourceSequenceDao(),
            checkpointDao = database.listCheckpointDao(),
            nextcloudBindingDao = database.nextcloudCollectionBindingDao(),
        )
        fixtures = seedBlockedLists()

        scheduler = mockk(relaxed = true)
        nextcloud = mockk(relaxed = true) {
            every { observeListBindings() } returns flowOf(emptyList())
            every { observeAccountConfigured() } returns MutableStateFlow(false)
        }
        viewModel = ListsViewModel(
            dao = database.listItemDao(),
            listNameDao = database.listNameDao(),
            scheduler = scheduler,
            appContext = context,
            listMutations = mutations,
            listsUiPreferences = ListsUiPreferences(
                dataStore = InMemoryPreferencesDataStore(),
                listNameDao = database.listNameDao(),
            ),
            nextcloud = nextcloud,
        ).apply { ioDispatcher = Dispatchers.IO }
        viewModelStore = ViewModelStore().apply { put("blocked-deletion", viewModel) }

        composeTestRule.setContent {
            MaterialTheme {
                ListsScreen(viewModel = viewModel)
            }
        }
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) viewModelStore.clear()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun blockedBulkDeleteShowsGuidanceAndPreservesSelectionListsItemsAndReminders() {
        fixtures.forEach { fixture ->
            composeTestRule.onNodeWithText(fixture.list.name).assertIsDisplayed()
        }

        composeTestRule.onNodeWithText(fixtures.first().list.name)
            .performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
                up()
            }
        composeTestRule.onNodeWithText("Select All").performClick()
        composeTestRule.onNodeWithText("3 selected").assertIsDisplayed()
        assertEquals(fixtures.map { it.list.id }.toSet(), viewModel.selectedListIds)

        composeTestRule.onNodeWithContentDescription("Delete selected").performClick()
        composeTestRule.onNodeWithText("Delete 3 lists?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            viewModel.nextcloudMessage != null
        }
        composeTestRule.onNodeWithText("3 lists could not be deleted.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(ListMutationBlockedException.UNAVAILABLE_MESSAGE, substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(ListMutationBlockedException.READ_ONLY_MESSAGE, substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(ListMutationBlockedException.UNSYNCED_MESSAGE, substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("3 selected").assertIsDisplayed()
        assertEquals(fixtures.map { it.list.id }.toSet(), viewModel.selectedListIds)
        fixtures.forEach { fixture ->
            composeTestRule.onNodeWithText(fixture.list.name).assertIsDisplayed()
        }
        runBlocking {
            fixtures.forEach { fixture ->
                assertEquals(fixture.list, database.listNameDao().getById(fixture.list.id))
                assertEquals(
                    fixture.items,
                    database.listItemDao().getAllByListAnyLifecycle(fixture.list.id),
                )
                assertEquals(
                    fixture.reminders,
                    database.listItemDao().getAllWithNotification(fixture.list.id),
                )
                assertEquals(
                    fixture.binding,
                    database.nextcloudCollectionBindingDao().get(fixture.list.collectionId),
                )
            }
        }
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test
    fun blockedSingleUnavailableDeleteShowsGuidanceAndPreservesListItemsBindingAndReminders() {
        val fixture = fixtures.single { !it.binding.remoteAvailable }
        openOverflowMenuFor(fixture.list.name)

        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("""Delete "${fixture.list.name}"?""").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            viewModel.nextcloudMessage == ListMutationBlockedException.UNAVAILABLE_MESSAGE
        }
        composeTestRule.onNodeWithText(
            ListMutationBlockedException.UNAVAILABLE_MESSAGE,
            substring = true,
        ).assertIsDisplayed()

        assertEquals(emptySet<Long>(), viewModel.selectedListIds)
        composeTestRule.onNodeWithText(fixture.list.name).assertIsDisplayed()
        runBlocking {
            assertEquals(fixture.list, database.listNameDao().getById(fixture.list.id))
            assertEquals(
                fixture.items,
                database.listItemDao().getAllByListAnyLifecycle(fixture.list.id),
            )
            assertEquals(
                fixture.reminders,
                database.listItemDao().getAllWithNotification(fixture.list.id),
            )
            assertEquals(
                fixture.binding,
                database.nextcloudCollectionBindingDao().get(fixture.list.collectionId),
            )
        }
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    private fun openOverflowMenuFor(listName: String) {
        val rowCenterY = composeTestRule.onNodeWithText(listName)
            .fetchSemanticsNode()
            .boundsInRoot.center.y
        val overflowMenus = composeTestRule.onAllNodesWithContentDescription(
            "More options",
            useUnmergedTree = true,
        )
        val menuIndex = overflowMenus.fetchSemanticsNodes().indexOfFirst { menu ->
            rowCenterY in menu.boundsInRoot.top..menu.boundsInRoot.bottom
        }
        assertTrue("Expected an overflow menu in $listName's row", menuIndex >= 0)
        overflowMenus[menuIndex].performClick()
    }

    private suspend fun seedBlockedLists(): List<BlockedListFixture> {
        val states = listOf(
            GuardState(
                suffix = "unavailable",
                remoteAvailable = false,
                remoteWritable = true,
                blockedUnsyncedAt = null,
            ),
            GuardState(
                suffix = "read-only",
                remoteAvailable = true,
                remoteWritable = false,
                blockedUnsyncedAt = null,
            ),
            GuardState(
                suffix = "stranded-unsynced",
                remoteAvailable = true,
                remoteWritable = true,
                blockedUnsyncedAt = 1L,
            ),
        )
        val reminderTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
        return states.map { state ->
            val listId = mutations.createCollection("Synthetic ${state.suffix}")
            val itemId = mutations.addItem(
                listId = listId,
                text = "Synthetic reminder ${state.suffix}",
                notificationTime = reminderTime,
            )
            val list = requireNotNull(database.listNameDao().getById(listId))
            val item = requireNotNull(database.listItemDao().getById(itemId))
            val binding = NextcloudCollectionBindingEntity(
                collectionId = list.collectionId,
                remoteHref = "https://fixture-${state.suffix}.invalid/tasks/",
                remoteTitle = list.canonicalTitle,
                remoteEtag = null,
                remoteLogicalClock = 0L,
                updatedAt = reminderTime,
                remoteWritable = state.remoteWritable,
                remoteAvailable = state.remoteAvailable,
                blockedUnsyncedAt = state.blockedUnsyncedAt,
            )
            database.nextcloudCollectionBindingDao().upsert(binding)
            BlockedListFixture(list, listOf(item), listOf(item), binding)
        }
    }

    private data class GuardState(
        val suffix: String,
        val remoteAvailable: Boolean,
        val remoteWritable: Boolean,
        val blockedUnsyncedAt: Long?,
    )

    private data class BlockedListFixture(
        val list: ListNameEntity,
        val items: List<ListItemEntity>,
        val reminders: List<ListItemEntity>,
        val binding: NextcloudCollectionBindingEntity,
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
