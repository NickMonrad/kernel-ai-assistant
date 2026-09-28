package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTextTest {
    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val listMutations = mockk<ListMutationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()
    private val preferences = testListsUiPreferences(dispatcher)
    private val nextcloudAdapter = mockk<NextcloudSyncAdapter>(relaxed = true).apply {
        every { observeListBindings() } returns flowOf(emptyList())
        every { observeAccountConfigured() } returns MutableStateFlow(false)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { dao.observeAll() } returns flowOf(emptyList())
        every { listNameDao.observeActiveLists() } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `updateItem forwards exact multiline text and description to persistence`() {
        val original = ListItemEntity(
            id = 7L,
            listId = 1L,
            text = "old text",
            description = "old description",
            checked = false,
        )
        val editedText = "  First line\nhttps://example.com/tasks/today\nLast line  "
        val editedDescription = "  Notes\nhttps://example.com/description?full=true\nEnd  "
        val viewModel = ListsViewModel(
            dao = dao,
            listNameDao = listNameDao,
            scheduler = scheduler,
            appContext = context,
            listMutations = listMutations,
            listsUiPreferences = preferences,
            nextcloud = nextcloudAdapter,
        ).apply { ioDispatcher = dispatcher }

        viewModel.updateItem(original.copy(text = editedText, description = editedDescription))

        coVerify(timeout = TimeUnit.SECONDS.toMillis(2)) {
            listMutations.updateItem(
                original.id,
                editedText,
                original.dueAt,
                original.isFavourite,
                original.notificationTime,
                editedDescription,
            )
        }
    }

    @Test
    fun `buildShareText retains exact multiline item text`() = runBlocking {
        val itemText = "  First line\nhttps://example.com/tasks/today\nLast line  "
        val item = ListItemEntity(
            id = 8L,
            listId = 1L,
            text = itemText,
            description = "Details\nhttps://example.com/details?full=true",
            checked = false,
        )
        coEvery { listNameDao.getById(1L) } returns ListNameEntity(id = 1L, name = "groceries")
        coEvery { dao.getAllByList(1L) } returns listOf(item)
        val viewModel = ListsViewModel(
            dao = dao,
            listNameDao = listNameDao,
            scheduler = scheduler,
            appContext = context,
            listMutations = listMutations,
            listsUiPreferences = preferences,
            nextcloud = nextcloudAdapter,
        ).apply { ioDispatcher = dispatcher }

        assertEquals("Groceries\n\n• $itemText\nDetails\nhttps://example.com/details?full=true", viewModel.buildShareText(1L))
    }

    @Test
    fun `selected sharing keeps dao order and includes only selected descriptions`() = runBlocking {
        val first = ListItemEntity(id = 11L, listId = 1L, text = "first", description = "https://example.com/first?full=true", itemId = "first")
        val selected = ListItemEntity(id = 12L, listId = 1L, text = "selected", description = "line one\nline two", itemId = "selected")
        val third = ListItemEntity(id = 13L, listId = 1L, text = "third", itemId = "third")
        coEvery { listNameDao.getById(1L) } returns ListNameEntity(id = 1L, name = "groceries")
        coEvery { dao.getAllByList(1L) } returns listOf(first, selected, third)
        val viewModel = ListsViewModel(
            dao = dao,
            listNameDao = listNameDao,
            scheduler = scheduler,
            appContext = context,
            listMutations = listMutations,
            listsUiPreferences = preferences,
            nextcloud = nextcloudAdapter,
        ).apply { ioDispatcher = dispatcher }

        assertEquals(
            "Groceries\n\n• selected\nline one\nline two",
            viewModel.buildShareText(1L, setOf(selected.id)),
        )
        viewModel.selectAllItems(listOf(first.id, selected.id, third.id))
        viewModel.exitItemMultiSelect()
        assertEquals(emptySet<Long>(), viewModel.selectedItemIds)
    }

}
