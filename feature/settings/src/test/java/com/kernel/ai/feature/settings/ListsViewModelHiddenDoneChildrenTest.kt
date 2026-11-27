package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.CheckedStateMutation
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A ticked sub-item under an unticked parent leaves the active list. The parent's note counts it,
 * search and the completed-only filter still reach it, and clearing done removes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelHiddenDoneChildrenTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val listMutations = mockk<ListMutationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val nextcloudAdapter = mockk<NextcloudSyncAdapter>(relaxed = true).apply {
        every { observeListBindings() } returns flowOf(emptyList())
        every { observeAccountConfigured() } returns MutableStateFlow(false)
    }
    private val items = MutableStateFlow(groceries(salt = false, sumac = true))
    private lateinit var viewModel: ListsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Built after setMain: the VM's viewModelScope must bind to the test dispatcher.
        viewModel = ListsViewModel(
            dao,
            listNameDao,
            scheduler,
            context,
            listMutations,
            testListsUiPreferences(dispatcher),
            nextcloudAdapter,
        ).apply { ioDispatcher = dispatcher }
        every { dao.observeAll() } returns flowOf(emptyList())
        every { listNameDao.observeActiveLists() } returns flowOf(emptyList())
        every { dao.observeByList(LIST_ID) } returns items
        viewModel.selectItemSort(ItemSort.MANUAL)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a ticked sub-item is hidden from its active parent and counted as done`() = runTest(dispatcher) {
        val hierarchy = observe()

        assertEquals(listOf("Pantry"), hierarchy.value.activeGroups.map { it.parent.text })
        assertEquals(listOf("Salt"), hierarchy.value.activeGroups.single().children.map { it.text })
        assertEquals(mapOf(PANTRY_ROW to 1), hierarchy.value.doneChildCounts)
        assertTrue(hierarchy.value.completedGroups.isEmpty())
    }

    @Test
    fun `unticking a done sub-item brings it back to the active list`() = runTest(dispatcher) {
        val hierarchy = observe()

        items.value = groceries(salt = false, sumac = false)

        assertEquals(listOf("Salt", "Sumac"), hierarchy.value.activeGroups.single().children.map { it.text })
        assertEquals(emptyMap<Long, Int>(), hierarchy.value.doneChildCounts)
    }

    @Test
    fun `ticking the parent moves the whole group to completed`() = runTest(dispatcher) {
        val hierarchy = observe()

        items.value = groceries(salt = true, sumac = true, pantry = true)

        assertTrue(hierarchy.value.activeGroups.isEmpty())
        assertEquals(listOf("Pantry"), hierarchy.value.completedGroups.map { it.parent.text })
        assertEquals(listOf("Salt", "Sumac"), hierarchy.value.completedGroups.single().children.map { it.text })
        assertEquals(emptyMap<Long, Int>(), hierarchy.value.doneChildCounts)
    }

    @Test
    fun `search finds a ticked sub-item under an active parent`() = runTest(dispatcher) {
        val hierarchy = observe()

        viewModel.setItemSearchQuery("sumac")

        assertEquals(listOf("Sumac"), hierarchy.value.activeGroups.single().children.map { it.text })
    }

    @Test
    fun `search on the parent name does not reveal its ticked sub-items`() = runTest(dispatcher) {
        val hierarchy = observe()

        viewModel.setItemSearchQuery("pantry")

        assertEquals(listOf("Salt"), hierarchy.value.activeGroups.single().children.map { it.text })
        assertEquals(mapOf(PANTRY_ROW to 1), hierarchy.value.doneChildCounts)
    }

    @Test
    fun `completed-only filter lists a ticked sub-item under an active parent`() = runTest(dispatcher) {
        // Set before observing: snapshot state changes are not re-emitted to plain unit-test collectors.
        viewModel.itemFilter = ItemFilter.COMPLETED_ONLY
        val hierarchy = observe()

        assertEquals(listOf("Sumac"), hierarchy.value.activeGroups.single().children.map { it.text })
        assertTrue(hierarchy.value.completedGroups.isEmpty())
    }

    @Test
    fun `clear done deletes ticked sub-items and leaves the open parent and sub-items`() = runTest(dispatcher) {
        coEvery { dao.getAllByList(LIST_ID) } returns groceries(salt = false, sumac = true)
        coEvery { listMutations.deleteItems(listOf(SUMAC_ROW)) } returns CheckedStateMutation()

        viewModel.clearChecked(LIST_ID)

        coVerify(exactly = 1) { listMutations.deleteItems(listOf(SUMAC_ROW)) }
    }

    /** Keeps the displayed hierarchy subscribed for the test, so [items] updates reach its value. */
    private fun TestScope.observe(): StateFlow<ListsViewModel.DisplayedHierarchy> =
        viewModel.observeDisplayedHierarchy(LIST_ID).also { flow ->
            backgroundScope.launch { flow.collect {} }
        }

    private fun groceries(salt: Boolean, sumac: Boolean, pantry: Boolean = false): List<ListItemEntity> = listOf(
        ListItemEntity(
            id = PANTRY_ROW,
            listId = LIST_ID,
            text = "Pantry",
            itemId = "pantry",
            checked = pantry,
            orderKey = "1",
        ),
        ListItemEntity(
            id = SALT_ROW,
            listId = LIST_ID,
            text = "Salt",
            itemId = "salt",
            checked = salt,
            parentItemId = "pantry",
            orderKey = "2",
        ),
        ListItemEntity(
            id = SUMAC_ROW,
            listId = LIST_ID,
            text = "Sumac",
            itemId = "sumac",
            checked = sumac,
            parentItemId = "pantry",
            orderKey = "3",
        ),
    )

    private companion object {
        const val LIST_ID = 1L
        const val PANTRY_ROW = 10L
        const val SALT_ROW = 11L
        const val SUMAC_ROW = 12L
    }
}
