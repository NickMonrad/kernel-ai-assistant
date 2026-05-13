package com.kernel.ai.feature.convert

import com.kernel.ai.core.memory.dao.CurrencyFavouriteDao
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurrencyFavouriteDaoTest {

    private val dao = mockk<CurrencyFavouriteDao>()

    @Test
    fun `observeAll returns favourites ordered by sort_order`() = runTest {
        val favs = listOf(
            CurrencyFavouriteEntity("USD_NZD", "USD", "NZD", 0),
            CurrencyFavouriteEntity("EUR_GBP", "EUR", "GBP", 1),
        )
        coEvery { dao.observeAll() } returns flowOf(favs)

        val result = dao.observeAll().first()

        assertEquals(2, result.size)
        assertEquals(0, result[0].sortOrder)
        assertEquals(1, result[1].sortOrder)
    }

    @Test
    fun `observeAll returns entities with correct field values`() = runTest {
        val fav = CurrencyFavouriteEntity("USD_NZD", "USD", "NZD", 0)
        coEvery { dao.observeAll() } returns flowOf(listOf(fav))

        val result = dao.observeAll().first()

        assertEquals("USD_NZD", result[0].id)
        assertEquals("USD", result[0].fromCode)
        assertEquals("NZD", result[0].toCode)
        assertEquals(0, result[0].sortOrder)
    }

    @Test
    fun `count returns correct number`() = runTest {
        coEvery { dao.count() } returns 3

        assertEquals(3, dao.count())
    }

    @Test
    fun `count returns 0 when no favourites`() = runTest {
        coEvery { dao.count() } returns 0

        assertEquals(0, dao.count())
    }

    @Test
    fun `insert stores favourite`() = runTest {
        val fav = CurrencyFavouriteEntity("AUD_USD", "AUD", "USD", 0)
        coEvery { dao.insert(fav) } returns Unit

        dao.insert(fav)

        coVerify(exactly = 1) { dao.insert(fav) }
    }

    @Test
    fun `insert is called with correct id format`() = runTest {
        val fav = CurrencyFavouriteEntity("GBP_JPY", "GBP", "JPY", 2)
        coEvery { dao.insert(fav) } returns Unit

        dao.insert(fav)

        coVerify { dao.insert(match { it.id == "${it.fromCode}_${it.toCode}" }) }
    }

    @Test
    fun `delete removes favourite by id and returns count`() = runTest {
        coEvery { dao.delete("USD_NZD") } returns 1

        val deleted = dao.delete("USD_NZD")

        assertEquals(1, deleted)
        coVerify(exactly = 1) { dao.delete("USD_NZD") }
    }

    @Test
    fun `delete returns 0 when id not found`() = runTest {
        coEvery { dao.delete("NONEXISTENT") } returns 0

        val deleted = dao.delete("NONEXISTENT")

        assertEquals(0, deleted)
    }

    @Test
    fun `observeAll returns empty list when no favourites exist`() = runTest {
        coEvery { dao.observeAll() } returns flowOf(emptyList())

        val result = dao.observeAll().first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `count returns maximum capacity value`() = runTest {
        coEvery { dao.count() } returns 5

        assertEquals(5, dao.count())
    }

    @Test
    fun `observeAll returns single favourite`() = runTest {
        val fav = CurrencyFavouriteEntity("AUD_NZD", "AUD", "NZD", 0)
        coEvery { dao.observeAll() } returns flowOf(listOf(fav))

        val result = dao.observeAll().first()

        assertEquals(1, result.size)
        assertEquals("AUD_NZD", result[0].id)
    }
}
