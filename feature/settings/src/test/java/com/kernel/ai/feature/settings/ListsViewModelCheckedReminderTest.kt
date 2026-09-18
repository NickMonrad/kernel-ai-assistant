package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.lists.CheckedStateMutation
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelCheckedReminderTest {
    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val listMutations = mockk<ListMutationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()
    private val preferences = testListsUiPreferences(dispatcher)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dao.observeAll() } returns flowOf(emptyList())
        every { listNameDao.observeActiveLists() } returns flowOf(emptyList())
    }

    /**
     * Every hierarchy interaction hops through [ListsViewModel.ioDispatcher] before touching the
     * repository. Binding the test dispatcher keeps that hop inside the test instead of resuming on
     * `Dispatchers.Main` after `resetMain`.
     */
    private fun testViewModel(preferences: ListsUiPreferences) = ListsViewModel(
        dao,
        listNameDao,
        scheduler,
        context,
        listMutations,
        preferences,
    ).apply { ioDispatcher = dispatcher }

    @Test
    fun `reorder and group entry point switches to manual order`() {
        val viewModel = testViewModel(preferences)
        viewModel.bindItemList(1L)
        viewModel.selectItemSort(ItemSort.NAME_ASC)
        viewModel.itemFilter = ItemFilter.FAVOURITES_ONLY
        viewModel.setItemSearchQuery("find")

        viewModel.enterManualHierarchyEditing()

        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
        assertEquals(ItemSort.MANUAL, savedItemSort(1L))
        assertEquals(ItemFilter.ALL, viewModel.itemFilter)
        assertEquals("", viewModel.itemSearchQuery.value)
        assertTrue(
            isHierarchyEditingEnabled(
                itemFilter = viewModel.itemFilter,
                searchQuery = viewModel.itemSearchQuery.value,
                isMultiSelectMode = false,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `completing a parent cancels reminders for every actual checked transition`() {
        val parent = item(1L, checked = false)
        val child = item(2L, checked = false, parentItemId = parent.itemId)
        coEvery { listMutations.setItemChecked(1L, true) } returns CheckedStateMutation(
            checkedIds = setOf(parent.id, child.id),
        )
        val viewModel = testViewModel(preferences)

        viewModel.toggleChecked(parent)

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) { scheduler.cancel(parent.id) }
        verify(timeout = TimeUnit.SECONDS.toMillis(2)) { scheduler.cancel(child.id) }
    }

    @Test
    fun `uncompleting cascaded items schedules only future actual unchecked transitions`() {
        val triggerAtMs = System.currentTimeMillis() + 60_000L
        val parent = item(1L, checked = true, notificationTime = triggerAtMs)
        val child = item(2L, checked = true, parentItemId = parent.itemId, notificationTime = triggerAtMs)
        val unrelated = item(3L, checked = true, notificationTime = triggerAtMs)
        val items = mapOf(parent.id to parent, child.id to child, unrelated.id to unrelated)
        coEvery { listMutations.setItemsChecked(listOf(parent.id), false) } returns CheckedStateMutation(
            uncheckedIds = setOf(parent.id, child.id),
        )
        coEvery { dao.getById(any()) } answers { items[firstArg()] }
        coEvery { listNameDao.getById(1L) } returns ListNameEntity(id = 1L, name = "groceries")
        val viewModel = testViewModel(preferences)
        viewModel.enterItemMultiSelect(parent.id)

        viewModel.unmarkSelectedItemsComplete()

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) {
            scheduler.schedule(parent.id, parent.text, parent.listId, "groceries", triggerAtMs)
        }
        verify(timeout = TimeUnit.SECONDS.toMillis(2)) {
            scheduler.schedule(child.id, child.text, child.listId, "groceries", triggerAtMs)
        }
        verify(exactly = 0) { scheduler.schedule(unrelated.id, unrelated.text, unrelated.listId, "groceries", triggerAtMs) }
    }

    @Test
    fun `indenting an incomplete item into a completed parent reschedules that parent's reminder`() {
        val triggerAtMs = System.currentTimeMillis() + 60_000L
        val parent = item(1L, checked = true, notificationTime = triggerAtMs)
        val preceding = item(2L, checked = true, parentItemId = parent.itemId)
        val newcomer = item(3L, checked = false)
        coEvery { listMutations.indentItem(newcomer.id, preceding.itemId, null) } returns CheckedStateMutation(
            uncheckedIds = setOf(parent.id),
        )
        coEvery { dao.getById(parent.id) } returns parent
        coEvery { listNameDao.getById(1L) } returns ListNameEntity(id = 1L, name = "groceries")
        val viewModel = testViewModel(preferences).apply { selectItemSort(ItemSort.MANUAL) }

        viewModel.indentItem(listOf(newcomer.id, preceding.id), newcomer, preceding)

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) {
            scheduler.schedule(parent.id, parent.text, parent.listId, "groceries", triggerAtMs)
        }
    }

    @Test
    fun `outdenting the only incomplete child cancels the completed parent's reminder`() {
        val parent = item(1L, checked = false)
        val open = item(2L, checked = false, parentItemId = parent.itemId)
        coEvery { listMutations.outdentItem(open.id, null) } returns CheckedStateMutation(
            checkedIds = setOf(parent.id),
        )
        val viewModel = testViewModel(preferences).apply { selectItemSort(ItemSort.MANUAL) }

        viewModel.outdentItem(listOf(open.id), open)

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) { scheduler.cancel(parent.id) }
    }

    @Test
    fun `a placement that changes no completion state touches no reminder`() {
        val newcomer = item(3L, checked = false)
        val preceding = item(2L, checked = false)
        coEvery { listMutations.indentItem(newcomer.id, preceding.itemId, null) } returns CheckedStateMutation()
        val viewModel = testViewModel(preferences).apply { selectItemSort(ItemSort.MANUAL) }

        viewModel.indentItem(listOf(newcomer.id, preceding.id), newcomer, preceding)

        verify(exactly = 0) { scheduler.cancel(any()) }
        verify(exactly = 0) { scheduler.schedule(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an automatic sort cross-group drag routes the completion changes from the baseline`() {
        val triggerAtMs = System.currentTimeMillis() + 60_000L
        val newParent = item(1L, checked = false, notificationTime = triggerAtMs)
        val oldParent = item(2L, checked = true, notificationTime = triggerAtMs)
        val dragged = item(3L, checked = false, parentItemId = oldParent.itemId)
        val rows = listOf(newParent, oldParent, dragged)
        coEvery { dao.getById(any()) } answers { rows.firstOrNull { it.id == firstArg() } }
        coEvery { dao.getAllByListUnordered(1L) } returns rows
        // Dragging the open child into the settled parent completes it and reopens the old parent.
        coEvery { listMutations.applyVisibleHierarchyOrder(1L, any()) } returns CheckedStateMutation(
            checkedIds = setOf(newParent.id),
            uncheckedIds = setOf(oldParent.id),
        )
        coEvery { listNameDao.getById(1L) } returns ListNameEntity(id = 1L, name = "groceries")
        val viewModel = testViewModel(preferences)

        viewModel.moveItemFromDrag(
            orderedRowIds = listOf(newParent.id, dragged.id, oldParent.id),
            draggedId = dragged.id,
        )

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) { scheduler.cancel(newParent.id) }
        verify(timeout = TimeUnit.SECONDS.toMillis(2)) {
            scheduler.schedule(oldParent.id, oldParent.text, oldParent.listId, "groceries", triggerAtMs)
        }
        assertEquals(ItemSort.MANUAL, viewModel.itemSort)
    }

    private fun savedItemSort(listId: Long): ItemSort = runBlocking { preferences.itemSortFor(listId) }

    private fun item(
        id: Long,
        checked: Boolean,
        parentItemId: String? = null,
        notificationTime: Long? = null,
    ) = ListItemEntity(
        id = id,
        listId = 1L,
        text = "item-$id",
        itemId = "stable-$id",
        checked = checked,
        parentItemId = parentItemId,
        notificationTime = notificationTime,
    )
}
