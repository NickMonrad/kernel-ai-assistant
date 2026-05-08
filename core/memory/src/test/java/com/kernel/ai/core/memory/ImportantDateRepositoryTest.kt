package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.ImportantDateDao
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImportantDateRepositoryTest {
    @Test
    fun `normalizeLabel strips possessives and leading articles`() {
        assertEquals("mum birthday", ImportantDateRepository.normalizeLabel("my mum's birthday"))
        assertEquals("parents anniversary", ImportantDateRepository.normalizeLabel("the parents' anniversary"))
    }

    @Test
    fun `save find and delete round trip through dao`() = runTest {
        val dao = FakeImportantDateDao()
        val repository = ImportantDateRepository(dao)

        repository.save(label = "My Mum's birthday", month = 3, day = 15, year = null)
        repository.save(label = "Our anniversary", month = 6, day = 22, year = 2018)

        val birthday = repository.findByLabel("mum's birthday")
        val anniversary = repository.findByLabel("our anniversary")

        requireNotNull(birthday)
        requireNotNull(anniversary)
        assertEquals("My Mum's birthday", birthday.label)
        assertEquals("mum birthday", birthday.normalizedLabel)
        assertEquals(3, birthday.month)
        assertEquals(15, birthday.day)
        assertNull(birthday.year)
        assertEquals(2018, anniversary.year)

        assertEquals(2, repository.getAll().size)
        assertEquals(1, repository.deleteByLabel("mum's birthday"))
        assertNull(repository.findByLabel("my mum's birthday"))
        assertEquals(1, repository.getAll().size)
    }

    private class FakeImportantDateDao : ImportantDateDao {
        private val rows = linkedMapOf<String, ImportantDateEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<ImportantDateEntity>> = flowOf(rows.values.toList())

        override suspend fun getAll(): List<ImportantDateEntity> = rows.values.toList()

        override suspend fun findByNormalizedLabel(normalizedLabel: String): ImportantDateEntity? = rows[normalizedLabel]

        override suspend fun insert(date: ImportantDateEntity) {
            rows[date.normalizedLabel] = date.copy(id = nextId++)
        }

        override suspend fun deleteByNormalizedLabel(normalizedLabel: String): Int =
            if (rows.remove(normalizedLabel) != null) 1 else 0
    }
}
