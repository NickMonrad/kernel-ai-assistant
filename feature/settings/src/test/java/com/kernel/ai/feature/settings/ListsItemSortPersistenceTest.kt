package com.kernel.ai.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Storage contract for the device-local per-list item sort preference. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsUiPreferencesTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakePreferencesDataStore()
    private val preferences = testListsUiPreferences(dispatcher, store)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unset list reads back as created newest`() {
        assertEquals(ItemSort.CREATED_NEWEST, runBlocking { preferences.itemSortFor(1L) })
    }

    @Test
    fun `a stored sort reads back after reload`() {
        runBlocking { preferences.setItemSort(1L, ItemSort.MANUAL) }

        val reloaded = testListsUiPreferences(dispatcher, store)

        assertEquals(ItemSort.MANUAL, runBlocking { reloaded.itemSortFor(1L) })
    }

    @Test
    fun `lists keep independent sorts`() {
        runBlocking {
            preferences.setItemSort(1L, ItemSort.MANUAL)
            preferences.setItemSort(2L, ItemSort.NAME_ASC)
        }

        assertEquals(ItemSort.MANUAL, runBlocking { preferences.itemSortFor(1L) })
        assertEquals(ItemSort.NAME_ASC, runBlocking { preferences.itemSortFor(2L) })
        assertEquals(ItemSort.CREATED_NEWEST, runBlocking { preferences.itemSortFor(3L) })
    }

    @Test
    fun `a sort name this build does not know reads back as created newest`() {
        runBlocking {
            store.edit { it[itemSortKeyOf(1L)] = "SORT_FROM_A_NEWER_BUILD" }
        }

        assertEquals(ItemSort.CREATED_NEWEST, runBlocking { preferences.itemSortFor(1L) })
    }
}

/** Preferences store whose reads block until [release], to exercise restore ordering. */
private class GatedPreferencesDataStore : FakePreferencesDataStore() {
    private val gate = CompletableDeferred<Unit>()

    override val data: Flow<Preferences> = flow {
        gate.await()
        emitAll(state)
    }

    fun release() = gate.complete(Unit)
}

/**
 * Per-list item sort persistence driven through the real ViewModel, so reopening a list is
 * covered end to end rather than only at the storage layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsItemSortPersistenceTest {
    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val listMutations = mockk<ListMutationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakePreferencesDataStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dao.observeAll() } returns flowOf(emptyList())
        every { listNameDao.observeActiveLists() } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModelOn(store: DataStore<Preferences>) = ListsViewModel(
        dao,
        listNameDao,
        scheduler,
        context,
        listMutations,
        testListsUiPreferences(dispatcher, store),
    )

    /** Opens [listId] the way the drill-in screen does: a fresh ViewModel bound to that list. */
    private fun openList(listId: Long) = viewModelOn(store).also { it.bindItemList(listId) }

    @Test
    fun `a list with no saved sort opens in created newest`() {
        assertEquals(ItemSort.CREATED_NEWEST, openList(1L).itemSort)
    }

    @Test
    fun `manual order survives reopening the list`() {
        openList(1L).enterManualHierarchyEditing()

        assertEquals(ItemSort.MANUAL, openList(1L).itemSort)
    }

    @Test
    fun `a non manual sort survives reopening the list`() {
        openList(1L).selectItemSort(ItemSort.DUE_SOONEST)

        assertEquals(ItemSort.DUE_SOONEST, openList(1L).itemSort)
    }

    @Test
    fun `each list remembers its own sort`() {
        openList(1L).selectItemSort(ItemSort.MANUAL)
        openList(2L).selectItemSort(ItemSort.NAME_ASC)

        assertEquals(ItemSort.MANUAL, openList(1L).itemSort)
        assertEquals(ItemSort.NAME_ASC, openList(2L).itemSort)
    }

    @Test
    fun `a list without a saved sort never inherits another list's sort`() {
        openList(1L).enterManualHierarchyEditing()

        assertEquals(ItemSort.CREATED_NEWEST, openList(2L).itemSort)
    }

    @Test
    fun `a slow restore for a previous list cannot overwrite the list opened now`() {
        val gated = GatedPreferencesDataStore()
        runBlocking { testListsUiPreferences(dispatcher, gated).setItemSort(1L, ItemSort.MANUAL) }
        val viewModel = viewModelOn(gated)

        viewModel.bindItemList(1L)
        viewModel.bindItemList(2L)
        gated.release()

        assertEquals(DEFAULT_ITEM_SORT, viewModel.itemSort)
    }
}
