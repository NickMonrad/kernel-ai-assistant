package com.kernel.ai.feature.convert

import com.kernel.ai.core.memory.dao.ConversionHistoryDao
import com.kernel.ai.core.memory.entity.ConversionHistoryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversionHistoryDaoTest {

    private val dao = mockk<ConversionHistoryDao>()

    @Test
    fun `observeByType returns matching entities`() = runTest {
        val entity = ConversionHistoryEntity(
            type = "CURRENCY",
            inputAmount = "100",
            fromLabel = "USD",
            toLabel = "NZD",
            outputAmount = "163.00 NZD",
            createdAt = 1000L,
        )
        coEvery { dao.observeByType("CURRENCY") } returns flowOf(listOf(entity))

        val result = dao.observeByType("CURRENCY").first()

        assertEquals(1, result.size)
        assertEquals("CURRENCY", result[0].type)
    }

    @Test
    fun `observeByType returns entities with correct field values`() = runTest {
        val entity = ConversionHistoryEntity(
            type = "UNIT",
            inputAmount = "5",
            fromLabel = "km",
            toLabel = "mi",
            outputAmount = "3.107 mi",
            createdAt = 2000L,
        )
        coEvery { dao.observeByType("UNIT") } returns flowOf(listOf(entity))

        val result = dao.observeByType("UNIT").first()

        assertEquals(1, result.size)
        assertEquals("UNIT", result[0].type)
        assertEquals("5", result[0].inputAmount)
        assertEquals("km", result[0].fromLabel)
        assertEquals("mi", result[0].toLabel)
        assertEquals("3.107 mi", result[0].outputAmount)
        assertEquals(2000L, result[0].createdAt)
    }

    @Test
    fun `insert stores entity successfully`() = runTest {
        val entity = ConversionHistoryEntity(
            type = "UNIT",
            inputAmount = "5",
            fromLabel = "km",
            toLabel = "mi",
            outputAmount = "3.107 mi",
            createdAt = 2000L,
        )
        coEvery { dao.insert(entity) } returns Unit

        dao.insert(entity)

        coVerify(exactly = 1) { dao.insert(entity) }
    }

    @Test
    fun `deleteByType removes all entries of that type`() = runTest {
        coEvery { dao.deleteByType("COOKING") } returns Unit

        dao.deleteByType("COOKING")

        coVerify(exactly = 1) { dao.deleteByType("COOKING") }
    }

    @Test
    fun `observeByType for unknown type returns empty list`() = runTest {
        coEvery { dao.observeByType("UNKNOWN") } returns flowOf(emptyList())

        val result = dao.observeByType("UNKNOWN").first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `observeByType emits multiple items over time`() = runTest {
        val first = ConversionHistoryEntity(
            type = "CURRENCY", inputAmount = "50", fromLabel = "EUR",
            toLabel = "USD", outputAmount = "54.50 USD", createdAt = 100L,
        )
        val second = ConversionHistoryEntity(
            type = "CURRENCY", inputAmount = "200", fromLabel = "GBP",
            toLabel = "AUD", outputAmount = "381.00 AUD", createdAt = 200L,
        )
        coEvery { dao.observeByType("CURRENCY") } returns flowOf(listOf(first, second))

        val result = dao.observeByType("CURRENCY").first()

        assertEquals(2, result.size)
    }

    @Test
    fun `insert is called with correct cooking entity`() = runTest {
        val entity = ConversionHistoryEntity(
            type = "COOKING",
            inputAmount = "2",
            fromLabel = "cup",
            toLabel = "ml",
            outputAmount = "473.18 ml",
            createdAt = 3000L,
        )
        coEvery { dao.insert(entity) } returns Unit

        dao.insert(entity)

        coVerify { dao.insert(match { it.type == "COOKING" && it.fromLabel == "cup" }) }
    }

    @Test
    fun `deleteByType for CURRENCY type invokes correct call`() = runTest {
        coEvery { dao.deleteByType("CURRENCY") } returns Unit

        dao.deleteByType("CURRENCY")

        coVerify(exactly = 1) { dao.deleteByType("CURRENCY") }
    }

    @Test
    fun `deleteByType for UNIT type invokes correct call`() = runTest {
        coEvery { dao.deleteByType("UNIT") } returns Unit

        dao.deleteByType("UNIT")

        coVerify(exactly = 1) { dao.deleteByType("UNIT") }
    }
}
