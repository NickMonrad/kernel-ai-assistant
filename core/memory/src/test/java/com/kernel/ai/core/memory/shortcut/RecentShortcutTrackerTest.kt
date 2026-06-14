package com.kernel.ai.core.memory.shortcut

import com.kernel.ai.core.memory.dao.RecentShortcutDao
import com.kernel.ai.core.memory.entity.RecentShortcutEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecentShortcutTrackerTest {

    private val dao = mockk<RecentShortcutDao>()
    private lateinit var tracker: RecentShortcutTracker

    @BeforeEach
    fun setup() {
        tracker = RecentShortcutTracker(dao)
    }

    @Test
    fun `observeAll returns recents newest first`() = runTest {
        val entities = listOf(
            RecentShortcutEntity(id = "clock", openedAt = 3000L),
            RecentShortcutEntity(id = "lists", openedAt = 2000L),
        )
        coEvery { dao.observeAll() } returns flowOf(entities)

        val result = tracker.observeAll().first()
        assertEquals(entities, result)
    }

    @Test
    fun `observeAll returns empty list when no recents`() = runTest {
        coEvery { dao.observeAll() } returns flowOf(emptyList())

        val result = tracker.observeAll().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `record upserts and trims to limit`() = runTest {
        coEvery { dao.upsert(any()) } returns Unit
        coEvery { dao.trimToLimit(RecentShortcutTracker.MAX_RECENTS) } returns 0

        tracker.record("lists")

        coVerify {
            dao.upsert(match { it.id == "lists" })
            dao.trimToLimit(RecentShortcutTracker.MAX_RECENTS)
        }
    }

    @Test
    fun `record deduplicates same ID`() = runTest {
        coEvery { dao.upsert(any()) } returns Unit
        coEvery { dao.trimToLimit(any()) } returns 0

        tracker.record("clock")
        tracker.record("clock")

        coVerify(exactly = 2) { dao.upsert(match { it.id == "clock" }) }
        coVerify(exactly = 2) { dao.trimToLimit(RecentShortcutTracker.MAX_RECENTS) }
    }

    @Test
    fun `MAX_RECENTS is 5`() {
        assertEquals(5, RecentShortcutTracker.MAX_RECENTS)
    }

    @Test
    fun `clear deletes all recents`() = runTest {
        coEvery { dao.deleteAll() } returns Unit

        tracker.clear()

        coVerify { dao.deleteAll() }
    }
}
