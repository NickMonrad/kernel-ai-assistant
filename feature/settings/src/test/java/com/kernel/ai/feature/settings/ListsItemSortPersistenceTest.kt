package com.kernel.ai.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.CheckedStateMutation
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
    ).apply { ioDispatcher = dispatcher }

    /** Opens [listId] the way the drill-in screen does: a fresh ViewModel bound to that list. */
    private fun openList(listId: Long) = viewModelOn(store).also { it.bindItemList(listId) }

    @Test
    fun `a drag under an automatic sort materialises the visible order and switches to manual`() {
        val a = row(1L, "stable-a", "0")
        val b = row(2L, "stable-b", "1")
        val c = row(3L, "stable-c", "2")
        val rows = listOf(c, b, a)
        coEvery { dao.getAllByListUnordered(1L) } returns rows
        rows.forEach { coEvery { dao.getById(it.id) } returns it }
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        // The automatic sort shows C, B, A; the drag released as B, C, A.
        viewModel.moveItemFromDrag(orderedRowIds = listOf(b.id, c.id, a.id), draggedId = c.id)

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        assertEquals(ItemSort.MANUAL, savedItemSort(1L))
        coVerify {
            listMutations.applyVisibleHierarchyOrder(
                1L,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(b.id, null),
                    // C is dragged between top-level rows, so only its order changes.
                    ListMutationRepository.VisibleHierarchyRow(c.id, null, reparent = false),
                    ListMutationRepository.VisibleHierarchyRow(a.id, null),
                ),
            )
        }
        coVerify(exactly = 0) { listMutations.moveItem(any(), any(), any()) }
    }

    @Test
    fun `a child drag under an automatic sort materialises it into the dropped group`() {
        val first = row(1L, "stable-first", "0")
        val firstChild = row(2L, "stable-first-child", "0", parentItemId = first.itemId)
        val second = row(4L, "stable-second", "1")
        val secondChild = row(3L, "stable-second-child", "0", parentItemId = second.itemId)
        coEvery { dao.getAllByListUnordered(1L) } returns listOf(first, firstChild, second, secondChild)
        listOf(first, firstChild, second, secondChild).forEach {
            coEvery { dao.getById(it.id) } returns it
        }
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        // Second's child was dragged up into the first group, released between the two groups.
        viewModel.moveItemFromDrag(
            orderedRowIds = listOf(first.id, secondChild.id, firstChild.id, second.id),
            draggedId = secondChild.id,
        )

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        coVerify {
            listMutations.applyVisibleHierarchyOrder(
                1L,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(first.id, null),
                    ListMutationRepository.VisibleHierarchyRow(secondChild.id, first.id, reparent = true),
                    ListMutationRepository.VisibleHierarchyRow(firstChild.id, first.id),
                    ListMutationRepository.VisibleHierarchyRow(second.id, null),
                ),
            )
        }
    }

    @Test
    fun `an indent under an automatic sort materialises and edits in one repository call`() {
        val parent = row(1L, "stable-parent", "0")
        val child = row(2L, "stable-child", "1", parentItemId = parent.itemId)
        val newcomer = row(3L, "stable-newcomer", "2")
        coEvery { dao.getAllByListUnordered(1L) } returns listOf(parent, child, newcomer)
        listOf(parent, child, newcomer).forEach { coEvery { dao.getById(it.id) } returns it }
        coEvery { listMutations.indentItem(any(), any(), any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        // The automatic sort shows the newcomer last, so it indents under the child above it.
        viewModel.indentItem(
            visibleRowIds = listOf(parent.id, child.id, newcomer.id),
            item = newcomer,
            precedingRow = child,
        )

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        assertEquals(ItemSort.MANUAL, savedItemSort(1L))
        coVerify {
            listMutations.indentItem(
                newcomer.id,
                child.itemId,
                ListMutationRepository.VisibleOrderBaseline(
                    1L,
                    listOf(
                        ListMutationRepository.VisibleHierarchyRow(parent.id, null),
                        ListMutationRepository.VisibleHierarchyRow(child.id, parent.id),
                        ListMutationRepository.VisibleHierarchyRow(newcomer.id, null),
                    ),
                ),
            )
        }
        // One transaction owns the baseline and the indent; the ViewModel must not write them apart.
        coVerify(exactly = 0) { listMutations.applyVisibleHierarchyOrder(any(), any()) }
    }

    @Test
    fun `a top-level drag of a row with a suppressed requested parent is not a reparent`() {
        val top = row(1L, "stable-a", "0")
        // X requests a parent that is not in the list, so the edge is suppressed and X is presented
        // and dragged as a top-level row.
        val suppressed = row(2L, "stable-x", "1", parentItemId = "stable-missing")
        val other = row(3L, "stable-b", "2")
        val rows = listOf(top, suppressed, other)
        coEvery { dao.getAllByListUnordered(1L) } returns rows
        rows.forEach { coEvery { dao.getById(it.id) } returns it }
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        viewModel.moveItemFromDrag(
            orderedRowIds = listOf(top.id, other.id, suppressed.id),
            draggedId = suppressed.id,
        )

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        coVerify {
            listMutations.applyVisibleHierarchyOrder(
                1L,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(top.id, null),
                    ListMutationRepository.VisibleHierarchyRow(other.id, null),
                    // Order only: the retained requested parent is not an explicit outdent.
                    ListMutationRepository.VisibleHierarchyRow(suppressed.id, null, reparent = false),
                ),
            )
        }
    }

    @Test
    fun `a same-parent child reorder under an automatic sort is not a reparent`() {
        val parent = row(1L, "stable-parent", "0")
        val first = row(2L, "stable-first", "0", parentItemId = parent.itemId)
        val second = row(3L, "stable-second", "1", parentItemId = parent.itemId)
        val rows = listOf(parent, first, second)
        coEvery { dao.getAllByListUnordered(1L) } returns rows
        rows.forEach { coEvery { dao.getById(it.id) } returns it }
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        // The second child is dragged above the first; it stays in the same group.
        viewModel.moveItemFromDrag(
            orderedRowIds = listOf(parent.id, second.id, first.id),
            draggedId = second.id,
        )

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        coVerify {
            listMutations.applyVisibleHierarchyOrder(
                1L,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(parent.id, null),
                    ListMutationRepository.VisibleHierarchyRow(second.id, parent.id, reparent = false),
                    ListMutationRepository.VisibleHierarchyRow(first.id, parent.id),
                ),
            )
        }
    }

    @Test
    fun `a cross-group child drag under an automatic sort still reparents`() {
        val first = row(1L, "stable-first", "0")
        val child = row(2L, "stable-child", "0", parentItemId = first.itemId)
        val second = row(4L, "stable-second", "1")
        val rows = listOf(first, child, second)
        coEvery { dao.getAllByListUnordered(1L) } returns rows
        rows.forEach { coEvery { dao.getById(it.id) } returns it }
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)

        viewModel.moveItemFromDrag(
            orderedRowIds = listOf(first.id, second.id, child.id),
            draggedId = child.id,
        )

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        coVerify {
            listMutations.applyVisibleHierarchyOrder(
                1L,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(first.id, null),
                    ListMutationRepository.VisibleHierarchyRow(second.id, null),
                    ListMutationRepository.VisibleHierarchyRow(child.id, second.id, reparent = true),
                ),
            )
        }
    }

    @Test
    fun `a drag under manual order applies a single placement without materialising`() {
        val a = row(1L, "stable-a", "0")
        val b = row(2L, "stable-b", "1")
        coEvery { dao.getAllByListUnordered(1L) } returns listOf(a, b)
        coEvery { dao.getById(a.id) } returns a
        coEvery { dao.getById(b.id) } returns b
        coEvery { listMutations.moveItem(any(), any(), any()) } returns CheckedStateMutation()
        val viewModel = openList(1L)
        viewModel.selectItemSort(ItemSort.MANUAL)

        viewModel.moveItemFromDrag(orderedRowIds = listOf(b.id, a.id), draggedId = b.id)

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        coVerify { listMutations.moveItem(b.id, null, any()) }
        coVerify(exactly = 0) { listMutations.applyVisibleHierarchyOrder(any(), any()) }
    }

    private fun savedItemSort(listId: Long): ItemSort = runBlocking { testListsUiPreferences(dispatcher, store).itemSortFor(listId) }

    private fun row(id: Long, itemId: String, orderKey: String, parentItemId: String? = null, listId: Long = 1L) = ListItemEntity(
        id = id,
        listId = listId,
        text = itemId,
        itemId = itemId,
        parentItemId = parentItemId,
        orderKey = orderKey,
        lifecycle = ListLifecycle.ACTIVE.name,
    )

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
