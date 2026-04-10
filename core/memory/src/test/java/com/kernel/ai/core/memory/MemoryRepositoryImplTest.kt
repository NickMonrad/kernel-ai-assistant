package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.repository.MemoryRepositoryImpl
import com.kernel.ai.core.memory.vector.VectorStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class MemoryRepositoryImplTest {

    private val episodicDao: EpisodicMemoryDao = mockk()
    private val coreDao: CoreMemoryDao = mockk()
    private val vectorStore: VectorStore = mockk()

    private lateinit var repository: MemoryRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = MemoryRepositoryImpl(episodicDao, coreDao, vectorStore)
    }

    /**
     * Configures all DAO mocks needed for the prune() call that is triggered
     * internally on every addEpisodicMemory / addCoreMemory invocation.
     * Pass counts <= 500 (episodic) and <= 200 (core) to avoid triggering the
     * deleteOldestBeyondLimit paths that are under test elsewhere.
     */
    private fun stubPrune(episodicCount: Int = 1, coreCount: Int = 0) {
        coEvery { episodicDao.deleteOlderThan(any()) } just Runs
        coEvery { episodicDao.count() } returns episodicCount
        coEvery { coreDao.count() } returns coreCount
    }

    // ─────────────────────────────── addEpisodicMemory ───────────────────────────────

    @Test
    fun `addEpisodicMemory without embedding — inserts entity, no vec upsert`() = runTest {
        coEvery { episodicDao.insert(any()) } returns 1L
        stubPrune()

        val id = repository.addEpisodicMemory("conv-1", "test content")

        assertTrue(id.isNotBlank(), "Returned ID should not be blank")
        coVerify(exactly = 1) { episodicDao.insert(any()) }
        verify(exactly = 0) { vectorStore.upsert(any(), any(), any()) }
    }

    @Test
    fun `addEpisodicMemory with embedding — inserts entity AND upserts to vec table`() = runTest {
        coEvery { episodicDao.insert(any()) } returns 42L
        every { vectorStore.createTable(any(), any()) } just Runs
        every { vectorStore.upsert(any(), any(), any()) } just Runs
        stubPrune()

        val id = repository.addEpisodicMemory("conv-1", "test content", floatArrayOf(0.1f, 0.2f, 0.3f))

        assertTrue(id.isNotBlank(), "Returned ID should not be blank")
        coVerify(exactly = 1) { episodicDao.insert(any()) }
        verify(exactly = 1) { vectorStore.upsert(any(), 42L, floatArrayOf(0.1f, 0.2f, 0.3f)) }
    }

    @Test
    fun `addEpisodicMemory with embedding — creates vec table on first call`() = runTest {
        coEvery { episodicDao.insert(any()) } returns 1L
        every { vectorStore.createTable(any(), any()) } just Runs
        every { vectorStore.upsert(any(), any(), any()) } just Runs
        stubPrune()

        repository.addEpisodicMemory("conv-1", "first content", floatArrayOf(0.1f, 0.2f))

        verify(exactly = 1) { vectorStore.createTable(any(), any()) }
    }

    @Test
    fun `addEpisodicMemory with embedding — does NOT re-create vec table on second call`() = runTest {
        coEvery { episodicDao.insert(any()) } returns 1L andThen 2L
        every { vectorStore.createTable(any(), any()) } just Runs
        every { vectorStore.upsert(any(), any(), any()) } just Runs
        stubPrune()

        repository.addEpisodicMemory("conv-1", "first", floatArrayOf(0.1f, 0.2f))
        repository.addEpisodicMemory("conv-1", "second", floatArrayOf(0.3f, 0.4f))

        verify(exactly = 1) { vectorStore.createTable(any(), any()) }
    }

    // ─────────────────────────────── addCoreMemory ───────────────────────────────────

    @Test
    fun `addCoreMemory without embedding — inserts entity, no vec upsert`() = runTest {
        coEvery { coreDao.insert(any()) } returns 1L
        stubPrune()

        val id = repository.addCoreMemory("user preference content")

        assertTrue(id.isNotBlank(), "Returned ID should not be blank")
        coVerify(exactly = 1) { coreDao.insert(any()) }
        verify(exactly = 0) { vectorStore.upsert(any(), any(), any()) }
    }

    @Test
    fun `addCoreMemory with embedding — inserts entity AND upserts to vec`() = runTest {
        coEvery { coreDao.insert(any()) } returns 7L
        every { vectorStore.createTable(any(), any()) } just Runs
        every { vectorStore.upsert(any(), any(), any()) } just Runs
        stubPrune()

        val id = repository.addCoreMemory("core fact", "user", floatArrayOf(0.5f, 0.6f))

        assertTrue(id.isNotBlank(), "Returned ID should not be blank")
        coVerify(exactly = 1) { coreDao.insert(any()) }
        verify(exactly = 1) { vectorStore.upsert(any(), 7L, floatArrayOf(0.5f, 0.6f)) }
    }

    // ─────────────────────────────── searchMemories ──────────────────────────────────

    @Test
    fun `searchMemories — returns empty list when vec tables not created`() = runTest {
        // No add calls made → no vec tables created → search should short-circuit
        val result = repository.searchMemories(floatArrayOf(0.1f, 0.2f, 0.3f))

        assertTrue(result.isEmpty(), "Expected empty result when no vec tables exist")
        verify(exactly = 0) { vectorStore.search(any(), any(), any()) }
    }

    // ─────────────────────────────── deleteCoreMemory ────────────────────────────────

    @Test
    fun `deleteCoreMemory — delegates to coreDao`() = runTest {
        coEvery { coreDao.delete(any()) } just Runs

        repository.deleteCoreMemory("core-memory-id-123")

        coVerify(exactly = 1) { coreDao.delete("core-memory-id-123") }
    }

    // ─────────────────────────────── clearEpisodicMemories ───────────────────────────

    @Test
    fun `clearEpisodicMemories — calls deleteOlderThan with zero to wipe all records`() = runTest {
        coEvery { episodicDao.deleteOlderThan(any()) } just Runs

        repository.clearEpisodicMemories()

        coVerify(exactly = 1) { episodicDao.deleteOlderThan(0L) }
    }

    // ─────────────────────────────── prune ───────────────────────────────────────────

    @Test
    fun `prune — deletes episodic memories older than 30 days`() = runTest {
        val cutoffSlot = slot<Long>()
        coEvery { episodicDao.deleteOlderThan(capture(cutoffSlot)) } just Runs
        coEvery { episodicDao.count() } returns 100   // under 500 limit
        coEvery { coreDao.count() } returns 10        // under 200 limit

        val beforePrune = System.currentTimeMillis()
        repository.prune()
        val afterPrune = System.currentTimeMillis()

        coVerify(exactly = 1) { episodicDao.deleteOlderThan(any()) }
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000L
        val expectedMin = beforePrune - thirtyDaysMs - 5_000L
        val expectedMax = afterPrune - thirtyDaysMs + 5_000L
        assertTrue(
            cutoffSlot.captured in expectedMin..expectedMax,
            "Expected cutoff ~30 days ago but was ${cutoffSlot.captured}"
        )
    }

    @Test
    fun `prune — deletes excess episodic beyond 500 limit`() = runTest {
        coEvery { episodicDao.deleteOlderThan(any()) } just Runs
        coEvery { episodicDao.count() } returns 600   // 100 over the 500 limit
        coEvery { episodicDao.deleteOldestBeyondLimit(any()) } just Runs
        coEvery { coreDao.count() } returns 10        // under 200 limit

        repository.prune()

        coVerify(exactly = 1) { episodicDao.deleteOldestBeyondLimit(100) }
    }

    @Test
    fun `prune — deletes excess core memories beyond 200 limit`() = runTest {
        coEvery { episodicDao.deleteOlderThan(any()) } just Runs
        coEvery { episodicDao.count() } returns 100   // under 500 limit
        coEvery { coreDao.count() } returns 250       // 50 over the 200 limit
        coEvery { coreDao.deleteOldestBeyondLimit(any()) } just Runs

        repository.prune()

        coVerify(exactly = 1) { coreDao.deleteOldestBeyondLimit(50) }
    }

    @Test
    fun `prune — no deletion when under both limits`() = runTest {
        coEvery { episodicDao.deleteOlderThan(any()) } just Runs
        coEvery { episodicDao.count() } returns 100   // well under 500
        coEvery { coreDao.count() } returns 50        // well under 200

        repository.prune()

        coVerify(exactly = 0) { episodicDao.deleteOldestBeyondLimit(any()) }
        coVerify(exactly = 0) { coreDao.deleteOldestBeyondLimit(any()) }
    }
}
