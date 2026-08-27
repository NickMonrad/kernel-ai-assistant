package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelBroadcastTest {

    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val itemFlow = MutableSharedFlow<List<ListItemEntity>>(extraBufferCapacity = 1)
    private val nameFlow = MutableSharedFlow<List<ListNameEntity>>(extraBufferCapacity = 1)
    private val broadcastCalls = mutableListOf<Int>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { dao.observeAll() } returns itemFlow
        every { listNameDao.observeActiveLists() } returns nameFlow
        every { context.sendBroadcast(any()) } answers { broadcastCalls.add(0); Unit }
    }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `broadcasts on item and name mutations, never on initial replay`() {
        ListsViewModel(dao, listNameDao, scheduler, context)

        itemFlow.tryEmit(emptyList())
        nameFlow.tryEmit(emptyList())
        assertEquals(0, broadcastCalls.size, "no broadcast on initial replay (got ${broadcastCalls.size})")

        itemFlow.tryEmit(
            listOf(ListItemEntity(id = 1L, listId = 1L, text = "milk", createdAt = 1L, updatedAt = 1L)),
        )
        assertEquals(1, broadcastCalls.size, "one broadcast on item mutation (got ${broadcastCalls.size})")

        nameFlow.tryEmit(emptyList())
        assertEquals(2, broadcastCalls.size, "one broadcast on name mutation (got ${broadcastCalls.size})")
    }
}
