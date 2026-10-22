package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.repository.ListMutationBlockedException
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelDeletionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val preferences = testListsUiPreferences(dispatcher)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blocked single deletion reports each Nextcloud guard and preserves reminders`() {
        val messages = listOf(
            ListMutationBlockedException.UNAVAILABLE_MESSAGE,
            ListMutationBlockedException.READ_ONLY_MESSAGE,
            ListMutationBlockedException.UNSYNCED_MESSAGE,
        )

        messages.forEach { message ->
            val item = reminderItem(id = 7L, listId = 9L)
            val dao = mockk<ListItemDao>(relaxed = true).apply {
                every { observeAll() } returns flowOf(emptyList())
                coEvery { getAllWithNotification(9L) } returns listOf(item)
            }
            val listNameDao = mockk<ListNameDao>(relaxed = true).apply {
                every { observeActiveLists() } returns flowOf(emptyList())
            }
            val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
            val mutations = mockk<ListMutationRepository>(relaxed = true).apply {
                coEvery { deleteCollection(9L) } throws ListMutationBlockedException(message)
            }
            val viewModel = viewModel(dao, listNameDao, scheduler, mutations)

            viewModel.deleteList(9L)

            assertEquals(message, viewModel.nextcloudMessage)
            verify(exactly = 0) { scheduler.cancel(any()) }
        }
    }

    @Test
    fun `successful local deletion commits before cancelling reminders on IO`() {
        val item = reminderItem(id = 7L, listId = 9L)
        val events = mutableListOf<String>()
        val reminderDispatcher = object : CoroutineDispatcher() {
            var dispatchDepth = 0
                private set

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatchDepth++
                try {
                    block.run()
                } finally {
                    dispatchDepth--
                }
            }
        }
        var cancellationDispatched = false
        val dao = mockk<ListItemDao>(relaxed = true).apply {
            every { observeAll() } returns flowOf(emptyList())
            coEvery { getAllWithNotification(9L) } returns listOf(item)
        }
        val listNameDao = mockk<ListNameDao>(relaxed = true).apply {
            every { observeActiveLists() } returns flowOf(emptyList())
        }
        val scheduler = mockk<ListNotificationScheduler>(relaxed = true).apply {
            every { cancel(item.id) } answers {
                events += "cancel"
                cancellationDispatched = reminderDispatcher.dispatchDepth > 0
                Unit
            }
        }
        val mutations = mockk<ListMutationRepository>(relaxed = true).apply {
            coEvery { deleteCollection(9L) } coAnswers {
                events += "delete"
                Unit
            }
        }

        val viewModel = viewModel(dao, listNameDao, scheduler, mutations).apply {
            ioDispatcher = reminderDispatcher
        }
        viewModel.deleteList(9L)

        assertEquals(listOf("delete", "cancel"), events)
        assertTrue(cancellationDispatched)
        assertEquals(null, viewModel.nextcloudMessage)
    }

    @Test
    fun `bulk deletion continues after blocked list and retains only its selection`() {
        val firstReminder = reminderItem(id = 11L, listId = 1L)
        val blockedReminder = reminderItem(id = 22L, listId = 2L)
        val events = mutableListOf<String>()
        val dao = mockk<ListItemDao>(relaxed = true).apply {
            every { observeAll() } returns flowOf(emptyList())
            coEvery { getAllWithNotification(any()) } coAnswers {
                listOf(if (firstArg<Long>() == 1L) firstReminder else blockedReminder)
            }
        }
        val listNameDao = mockk<ListNameDao>(relaxed = true).apply {
            every { observeActiveLists() } returns flowOf(emptyList())
        }
        val scheduler = mockk<ListNotificationScheduler>(relaxed = true).apply {
            every { cancel(any()) } answers { events += "cancel-${firstArg<Long>()}" }
        }
        val mutations = mockk<ListMutationRepository>(relaxed = true).apply {
            coEvery { deleteCollection(any()) } coAnswers {
                when (val listId = firstArg<Long>()) {
                    1L -> events += "delete-1"
                    2L -> {
                        events += "delete-2"
                        throw ListMutationBlockedException(ListMutationBlockedException.UNAVAILABLE_MESSAGE)
                    }
                    else -> error("Unexpected list id $listId")
                }
            }
        }
        val viewModel = viewModel(dao, listNameDao, scheduler, mutations)
        viewModel.selectAllLists(listOf(1L, 2L))

        viewModel.deleteSelectedLists()

        assertEquals(listOf("delete-1", "cancel-11", "delete-2"), events)
        assertEquals(setOf(2L), viewModel.selectedListIds)
        assertTrue(viewModel.nextcloudMessage.orEmpty().contains("Deleted 1 list"))
        assertTrue(viewModel.nextcloudMessage.orEmpty().contains(ListMutationBlockedException.UNAVAILABLE_MESSAGE))
    }

    @Test
    fun `blocked item deletion preserves selection and does not cancel reminder`() {
        val item = reminderItem(id = 7L, listId = 9L)
        val dao = mockk<ListItemDao>(relaxed = true).apply {
            every { observeAll() } returns flowOf(emptyList())
        }
        val listNameDao = mockk<ListNameDao>(relaxed = true).apply {
            every { observeActiveLists() } returns flowOf(emptyList())
        }
        val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
        val mutations = mockk<ListMutationRepository>(relaxed = true).apply {
            coEvery { deleteItems(listOf(item.id)) } throws
                ListMutationBlockedException(ListMutationBlockedException.UNSYNCED_MESSAGE)
        }
        val viewModel = viewModel(dao, listNameDao, scheduler, mutations)
        viewModel.enterItemMultiSelect(item.id)

        viewModel.deleteSelectedItems()

        assertEquals(setOf(item.id), viewModel.selectedItemIds)
        assertEquals(ListMutationBlockedException.UNSYNCED_MESSAGE, viewModel.nextcloudMessage)
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    private fun viewModel(
        dao: ListItemDao,
        listNameDao: ListNameDao,
        scheduler: ListNotificationScheduler,
        mutations: ListMutationRepository,
    ) = ListsViewModel(
        dao = dao,
        listNameDao = listNameDao,
        scheduler = scheduler,
        appContext = mockk<Context>(relaxed = true),
        listMutations = mutations,
        listsUiPreferences = preferences,
        nextcloud = mockk<NextcloudSyncAdapter>(relaxed = true).apply {
            every { observeListBindings() } returns flowOf(emptyList())
            every { observeAccountConfigured() } returns MutableStateFlow(false)
        },
    ).apply { ioDispatcher = dispatcher }

    private fun reminderItem(id: Long, listId: Long) = ListItemEntity(
        id = id,
        listId = listId,
        text = "synthetic reminder",
        notificationTime = 60_000L,
    )
}
