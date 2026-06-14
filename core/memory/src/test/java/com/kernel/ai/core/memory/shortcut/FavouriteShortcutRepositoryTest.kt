package com.kernel.ai.core.memory.shortcut

import com.kernel.ai.core.memory.dao.FavouriteShortcutDao
import com.kernel.ai.core.memory.entity.FavouriteShortcutEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FavouriteShortcutRepositoryTest {

    private val dao = mockk<FavouriteShortcutDao>()
    private lateinit var repository: FavouriteShortcutRepository

    @BeforeEach
    fun setup() {
        repository = FavouriteShortcutRepository(dao)
    }

    @Test
    fun `observeAll returns favourites from dao`() = runTest {
        val entities = listOf(
            FavouriteShortcutEntity(id = "lists", sortOrder = 0, addedAt = 1000L),
            FavouriteShortcutEntity(id = "notes", sortOrder = 1, addedAt = 2000L),
        )
        coEvery { dao.observeAll() } returns flowOf(entities)

        val result = repository.observeAll().first()
        assertEquals(entities, result)
    }

    @Test
    fun `observeAll returns empty list when no favourites`() = runTest {
        coEvery { dao.observeAll() } returns flowOf(emptyList())

        val result = repository.observeAll().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllIds returns set of IDs`() = runTest {
        coEvery { dao.getAllIds() } returns listOf("lists", "notes", "clock")

        val result = repository.getAllIds()
        assertEquals(setOf("lists", "notes", "clock"), result)
    }

    @Test
    fun `getAllIds returns empty set when no favourites`() = runTest {
        coEvery { dao.getAllIds() } returns emptyList()

        val result = repository.getAllIds()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `isFavourited returns true when favourited`() = runTest {
        coEvery { dao.isFavourited("lists") } returns true
        assertTrue(repository.isFavourited("lists"))
    }

    @Test
    fun `isFavourited returns false when not favourited`() = runTest {
        coEvery { dao.isFavourited("unknown") } returns false
        assertFalse(repository.isFavourited("unknown"))
    }

    @Test
    fun `toggle adds favourite when not favourited`() = runTest {
        coEvery { dao.isFavourited("lists") } returns false
        coEvery { dao.count() } returns 2
        coEvery { dao.insert(any()) } returns Unit

        val result = repository.toggle("lists")

        assertTrue(result)
        coVerify { dao.insert(match { it.id == "lists" && it.sortOrder == 2 }) }
    }

    @Test
    fun `toggle removes favourite when already favourited`() = runTest {
        coEvery { dao.isFavourited("lists") } returns true
        coEvery { dao.delete("lists") } returns 1

        val result = repository.toggle("lists")

        assertFalse(result)
        coVerify { dao.delete("lists") }
    }

    @Test
    fun `add inserts when not already favourited`() = runTest {
        coEvery { dao.isFavourited("lists") } returns false
        coEvery { dao.count() } returns 0
        coEvery { dao.insert(any()) } returns Unit

        repository.add("lists")

        coVerify { dao.insert(match { it.id == "lists" && it.sortOrder == 0 }) }
    }

    @Test
    fun `add is no-op when already favourited`() = runTest {
        coEvery { dao.isFavourited("lists") } returns true

        repository.add("lists")

        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `remove deletes by id`() = runTest {
        coEvery { dao.delete("lists") } returns 1

        repository.remove("lists")

        coVerify { dao.delete("lists") }
    }
}
