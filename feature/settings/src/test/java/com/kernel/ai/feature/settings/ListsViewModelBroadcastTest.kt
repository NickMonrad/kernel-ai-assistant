package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.entity.ListItemEntity
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelBroadcastTest {

    private val dao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<com.kernel.ai.core.memory.dao.ListNameDao>(relaxed = true)
    private val scheduler = mockk<ListNotificationScheduler>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val flow = MutableSharedFlow<List<ListItemEntity>>(extraBufferCapacity = 1)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { dao.observeAll() } returns flow
    }

    @AfterEach
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `broadcasts lists data changed whenever the list store emits`() {
        ListsViewModel(dao, listNameDao, scheduler, context)

        flow.tryEmit(emptyList())
        verify(exactly = 1) { context.sendBroadcast(any()) }

        flow.tryEmit(
            listOf(
                ListItemEntity(id = 1L, listId = 1L, text = "milk", createdAt = 1L, updatedAt = 1L),
            ),
        )
        verify(exactly = 2) { context.sendBroadcast(any()) }
    }
}
