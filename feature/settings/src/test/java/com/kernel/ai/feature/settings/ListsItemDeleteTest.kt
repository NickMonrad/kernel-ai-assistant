package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The list-item row keeps its own delete action. It must go through the hierarchy-aware mutation
 * seam (which records the tombstone and promotes surviving children of a deleted parent) rather
 * than a raw DAO delete, and it must drop any pending reminder for the deleted row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListsItemDeleteTest {
    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val listMutations = mockk<ListMutationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

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

    private fun testViewModel() = ListsViewModel(
        dao,
        listNameDao,
        scheduler,
        context,
        listMutations,
        testListsUiPreferences(dispatcher),
    ).apply { ioDispatcher = dispatcher }

    @Test
    fun `deleting a row cancels the reminder it was holding`() {
        val child = item(id = 2L, parentItemId = "stable-1", notificationTime = 1_760_000_000_000L)

        testViewModel().deleteItem(child)

        verify(timeout = TimeUnit.SECONDS.toMillis(2)) { scheduler.cancel(child.id) }
    }

    @Test
    fun `deleting a row records the delete through the hierarchy seam, never the raw DAO`() {
        val parent = item(id = 1L)
        val child = item(id = 2L, parentItemId = parent.itemId)

        val viewModel = testViewModel()
        viewModel.deleteItem(parent)
        viewModel.deleteItem(child)

        // The seam owns tombstone recording and surviving-child promotion. The launch hop is
        // unconfined in this harness, so the seam call has already run on return.
        coVerify(exactly = 1) { listMutations.deleteItem(parent.id) }
        coVerify(exactly = 1) { listMutations.deleteItem(child.id) }
        coVerify(exactly = 0) { dao.deleteItem(any()) }
    }

    private fun item(
        id: Long,
        parentItemId: String? = null,
        notificationTime: Long? = null,
    ) = ListItemEntity(
        id = id,
        listId = 1L,
        text = "item-$id",
        itemId = "stable-$id",
        checked = false,
        parentItemId = parentItemId,
        notificationTime = notificationTime,
    )
}
