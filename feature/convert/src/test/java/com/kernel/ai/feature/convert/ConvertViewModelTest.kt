package com.kernel.ai.feature.convert

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kernel.ai.core.memory.dao.ConversionHistoryDao
import com.kernel.ai.core.memory.dao.CurrencyFavouriteDao
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import com.kernel.ai.core.skills.natives.CookingConversionService
import com.kernel.ai.core.skills.natives.CurrencyConversionService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConvertViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var currencyService: CurrencyConversionService
    private lateinit var cookingService: CookingConversionService
    private lateinit var historyDao: ConversionHistoryDao
    private lateinit var favouriteDao: CurrencyFavouriteDao
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var viewModel: ConvertViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        currencyService = mockk(relaxed = true)
        cookingService = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        favouriteDao = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)

        // Default DAO stubs
        coEvery { historyDao.observeByType("CURRENCY") } returns flowOf(emptyList())
        coEvery { historyDao.observeByType("UNIT") } returns flowOf(emptyList())
        coEvery { historyDao.observeByType("COOKING") } returns flowOf(emptyList())
        coEvery { favouriteDao.observeAll() } returns flowOf(emptyList())
        coEvery { favouriteDao.count() } returns 0

        // Default service stubs
        coEvery { cookingService.allSupportedUnits() } returns emptyList()
        coEvery { currencyService.getSupportedCurrencies() } returns emptyMap()

        // DataStore stub
        val prefs = mockk<Preferences>(relaxed = true)
        coEvery { prefs[any<Preferences.Key<Boolean>>()] } returns null
        coEvery { dataStore.data } returns flowOf(prefs)
        coEvery { dataStore.edit(any()) } returns prefs

        viewModel = ConvertViewModel(
            currencyService = currencyService,
            cookingService = cookingService,
            conversionHistoryDao = historyDao,
            currencyFavouriteDao = favouriteDao,
            dataStore = dataStore,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Initial State ─────────────────────────────────────────────────────────

    @Test
    fun `initial state has currency tab selected`() {
        assertEquals(ConvertTab.CURRENCY, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `initial state has USD as fromUnit`() {
        assertEquals("USD", viewModel.uiState.value.fromUnit)
    }

    @Test
    fun `initial state has NZD as toUnit`() {
        assertEquals("NZD", viewModel.uiState.value.toUnit)
    }

    @Test
    fun `initial state has empty amount`() {
        assertEquals("", viewModel.uiState.value.amount)
    }

    @Test
    fun `initial state has no result`() {
        assertNull(viewModel.uiState.value.result)
    }

    @Test
    fun `initial state has empty currency history`() {
        assertTrue(viewModel.uiState.value.currencyHistory.isEmpty())
    }

    @Test
    fun `initial state has no favourite error`() {
        assertFalse(viewModel.uiState.value.showFavouriteError)
    }

    // ─── Tab Selection ─────────────────────────────────────────────────────────

    @Test
    fun `switching to unit tab updates selectedTab`() = runTest {
        viewModel.onTabSelected(ConvertTab.UNIT)
        advanceUntilIdle()

        assertEquals(ConvertTab.UNIT, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `switching to unit tab sets km as fromUnit`() = runTest {
        viewModel.onTabSelected(ConvertTab.UNIT)
        advanceUntilIdle()

        assertEquals("km", viewModel.uiState.value.fromUnit)
    }

    @Test
    fun `switching to unit tab sets mi as toUnit`() = runTest {
        viewModel.onTabSelected(ConvertTab.UNIT)
        advanceUntilIdle()

        assertEquals("mi", viewModel.uiState.value.toUnit)
    }

    @Test
    fun `switching to cooking tab updates selectedTab`() = runTest {
        viewModel.onTabSelected(ConvertTab.COOKING)
        advanceUntilIdle()

        assertEquals(ConvertTab.COOKING, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun `switching to cooking tab sets cup as fromUnit`() = runTest {
        viewModel.onTabSelected(ConvertTab.COOKING)
        advanceUntilIdle()

        assertEquals("cup", viewModel.uiState.value.fromUnit)
    }

    @Test
    fun `switching to cooking tab sets g as toUnit`() = runTest {
        viewModel.onTabSelected(ConvertTab.COOKING)
        advanceUntilIdle()

        assertEquals("g", viewModel.uiState.value.toUnit)
    }

    @Test
    fun `switching tabs clears result`() = runTest {
        viewModel.onTabSelected(ConvertTab.UNIT)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.result)
    }

    // ─── Amount Input ──────────────────────────────────────────────────────────

    @Test
    fun `onAmountChanged updates amount in state immediately`() = runTest {
        viewModel.onAmountChanged("42")

        assertEquals("42", viewModel.uiState.value.amount)
    }

    @Test
    fun `onAmountChanged with empty string clears amount`() = runTest {
        viewModel.onAmountChanged("100")
        viewModel.onAmountChanged("")

        assertEquals("", viewModel.uiState.value.amount)
    }

    // ─── From / To Changes ─────────────────────────────────────────────────────

    @Test
    fun `onFromChanged updates fromUnit in state`() = runTest {
        viewModel.onFromChanged("GBP")
        advanceUntilIdle()

        assertEquals("GBP", viewModel.uiState.value.fromUnit)
    }

    @Test
    fun `onToChanged updates toUnit in state`() = runTest {
        viewModel.onToChanged("EUR")
        advanceUntilIdle()

        assertEquals("EUR", viewModel.uiState.value.toUnit)
    }

    // ─── Swap ──────────────────────────────────────────────────────────────────

    @Test
    fun `onSwap exchanges fromUnit and toUnit`() = runTest {
        viewModel.onFromChanged("GBP")
        viewModel.onToChanged("EUR")
        advanceUntilIdle()

        viewModel.onSwap()
        advanceUntilIdle()

        assertEquals("EUR", viewModel.uiState.value.fromUnit)
        assertEquals("GBP", viewModel.uiState.value.toUnit)
    }

    @Test
    fun `onSwap with default values swaps USD and NZD`() = runTest {
        viewModel.onSwap()
        advanceUntilIdle()

        assertEquals("NZD", viewModel.uiState.value.fromUnit)
        assertEquals("USD", viewModel.uiState.value.toUnit)
    }

    // ─── Favourites ────────────────────────────────────────────────────────────

    @Test
    fun `addFavourite when count equals 5 sets showFavouriteError`() = runTest {
        coEvery { favouriteDao.count() } returns 5

        viewModel.addFavourite("USD", "NZD")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showFavouriteError)
    }

    @Test
    fun `addFavourite when count equals 5 does not insert into dao`() = runTest {
        coEvery { favouriteDao.count() } returns 5

        viewModel.addFavourite("USD", "NZD")
        advanceUntilIdle()

        coVerify(exactly = 0) { favouriteDao.insert(any()) }
    }

    @Test
    fun `addFavourite when count is below 5 inserts into dao`() = runTest {
        coEvery { favouriteDao.count() } returns 2
        coEvery { favouriteDao.insert(any()) } returns Unit

        viewModel.addFavourite("USD", "NZD")
        advanceUntilIdle()

        coVerify { favouriteDao.insert(match { it.fromCode == "USD" && it.toCode == "NZD" }) }
    }

    @Test
    fun `addFavourite when count is below 5 does not set error flag`() = runTest {
        coEvery { favouriteDao.count() } returns 2
        coEvery { favouriteDao.insert(any()) } returns Unit

        viewModel.addFavourite("USD", "NZD")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showFavouriteError)
    }

    @Test
    fun `addFavourite inserts entity with correct id format`() = runTest {
        coEvery { favouriteDao.count() } returns 1
        coEvery { favouriteDao.insert(any()) } returns Unit

        viewModel.addFavourite("AUD", "JPY")
        advanceUntilIdle()

        coVerify {
            favouriteDao.insert(
                match { it.id == "AUD_JPY" && it.fromCode == "AUD" && it.toCode == "JPY" }
            )
        }
    }

    @Test
    fun `addFavourite at exactly count 4 inserts successfully`() = runTest {
        coEvery { favouriteDao.count() } returns 4
        coEvery { favouriteDao.insert(any()) } returns Unit

        viewModel.addFavourite("EUR", "CHF")
        advanceUntilIdle()

        coVerify(exactly = 1) { favouriteDao.insert(any()) }
        assertFalse(viewModel.uiState.value.showFavouriteError)
    }

    @Test
    fun `dismissFavouriteError clears the error flag`() = runTest {
        coEvery { favouriteDao.count() } returns 5
        viewModel.addFavourite("USD", "NZD")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showFavouriteError)

        viewModel.dismissFavouriteError()

        assertFalse(viewModel.uiState.value.showFavouriteError)
    }

    @Test
    fun `removeFavourite calls dao delete with correct id`() = runTest {
        coEvery { favouriteDao.delete(any()) } returns 1

        viewModel.removeFavourite("USD_NZD")
        advanceUntilIdle()

        coVerify { favouriteDao.delete("USD_NZD") }
    }

    @Test
    fun `removeFavourite with arbitrary id delegates to dao`() = runTest {
        coEvery { favouriteDao.delete(any()) } returns 1

        viewModel.removeFavourite("EUR_GBP")
        advanceUntilIdle()

        coVerify { favouriteDao.delete("EUR_GBP") }
    }

    // ─── History ───────────────────────────────────────────────────────────────

    @Test
    fun `clearHistory for currency tab deletes CURRENCY type`() = runTest {
        coEvery { historyDao.deleteByType(any()) } returns Unit

        viewModel.clearHistory(ConvertTab.CURRENCY)
        advanceUntilIdle()

        coVerify { historyDao.deleteByType("CURRENCY") }
    }

    @Test
    fun `clearHistory for unit tab deletes UNIT type`() = runTest {
        coEvery { historyDao.deleteByType(any()) } returns Unit

        viewModel.clearHistory(ConvertTab.UNIT)
        advanceUntilIdle()

        coVerify { historyDao.deleteByType("UNIT") }
    }

    @Test
    fun `clearHistory for cooking tab deletes COOKING type`() = runTest {
        coEvery { historyDao.deleteByType(any()) } returns Unit

        viewModel.clearHistory(ConvertTab.COOKING)
        advanceUntilIdle()

        coVerify { historyDao.deleteByType("COOKING") }
    }

    @Test
    fun `clearHistory does not delete other types`() = runTest {
        coEvery { historyDao.deleteByType(any()) } returns Unit

        viewModel.clearHistory(ConvertTab.CURRENCY)
        advanceUntilIdle()

        coVerify(exactly = 0) { historyDao.deleteByType("UNIT") }
        coVerify(exactly = 0) { historyDao.deleteByType("COOKING") }
    }

    // ─── Conversion debounce ────────────────────────────────────────────────────

    @Test
    fun `convert is called after 300ms debounce on amount change`() = runTest {
        coEvery { currencyService.convert(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { historyDao.insert(any()) } returns Unit

        viewModel.onAmountChanged("100")
        advanceTimeBy(300)
        advanceUntilIdle()

        coVerify { currencyService.convert("100", "USD", "NZD") }
    }

    @Test
    fun `convert is not called before debounce delay elapses`() = runTest {
        viewModel.onAmountChanged("100")
        advanceTimeBy(299)

        coVerify(exactly = 0) { currencyService.convert(any(), any(), any()) }
    }

    @Test
    fun `convert result is Loading then resolves`() = runTest {
        coEvery { currencyService.convert(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { historyDao.insert(any()) } returns Unit

        viewModel.onAmountChanged("50")
        advanceTimeBy(300)
        advanceUntilIdle()

        // After conversion completes, result should not be null
        val result = viewModel.uiState.value.result
        assertTrue(result != null)
    }

    @Test
    fun `convert shows error result when currency service throws`() = runTest {
        coEvery { currencyService.convert(any(), any(), any()) } throws
            IllegalArgumentException("Unsupported currency")

        viewModel.onAmountChanged("100")
        advanceTimeBy(300)
        advanceUntilIdle()

        val result = viewModel.uiState.value.result
        assertTrue(result is ConversionResult.Error)
        assertEquals("Unsupported currency", (result as ConversionResult.Error).message)
    }

    // ─── Refresh Rates ─────────────────────────────────────────────────────────

    @Test
    fun `refreshRates calls evictRatesCache on currency service`() = runTest {
        every { currencyService.evictRatesCache() } just Runs

        viewModel.refreshRates()
        advanceUntilIdle()

        verify { currencyService.evictRatesCache() }
    }

    // ─── Ingredient ────────────────────────────────────────────────────────────

    @Test
    fun `onIngredientChanged updates cookingIngredient in state`() = runTest {
        viewModel.onIngredientChanged("flour")

        assertEquals("flour", viewModel.uiState.value.cookingIngredient)
    }

    @Test
    fun `onIngredientChanged with empty string clears ingredient`() = runTest {
        viewModel.onIngredientChanged("sugar")
        viewModel.onIngredientChanged("")

        assertEquals("", viewModel.uiState.value.cookingIngredient)
    }

    // ─── Australian Tablespoon ─────────────────────────────────────────────────

    @Test
    fun `toggleAustralianTablespoon calls dataStore edit`() = runTest {
        val prefs = mockk<Preferences>(relaxed = true)
        coEvery { dataStore.edit(any()) } returns prefs

        viewModel.toggleAustralianTablespoon()
        advanceUntilIdle()

        coVerify { dataStore.edit(any()) }
    }

    // ─── History collected into uiState ────────────────────────────────────────

    @Test
    fun `currency history from dao is reflected in uiState`() = runTest {
        val entity = com.kernel.ai.core.memory.entity.ConversionHistoryEntity(
            type = "CURRENCY",
            inputAmount = "10",
            fromLabel = "USD",
            toLabel = "NZD",
            outputAmount = "16.30 NZD",
            createdAt = 999L,
        )
        coEvery { historyDao.observeByType("CURRENCY") } returns flowOf(listOf(entity))

        // Re-create ViewModel so it picks up updated stub
        val prefs = mockk<Preferences>(relaxed = true)
        coEvery { prefs[any<Preferences.Key<Boolean>>()] } returns null
        coEvery { dataStore.data } returns flowOf(prefs)
        val vm = ConvertViewModel(
            currencyService = currencyService,
            cookingService = cookingService,
            conversionHistoryDao = historyDao,
            currencyFavouriteDao = favouriteDao,
            dataStore = dataStore,
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.currencyHistory.size)
        assertEquals("CURRENCY", vm.uiState.value.currencyHistory[0].type)
    }

    @Test
    fun `currency favourites from dao are reflected in uiState`() = runTest {
        val fav = CurrencyFavouriteEntity("USD_NZD", "USD", "NZD", 0)
        coEvery { favouriteDao.observeAll() } returns flowOf(listOf(fav))

        val prefs = mockk<Preferences>(relaxed = true)
        coEvery { prefs[any<Preferences.Key<Boolean>>()] } returns null
        coEvery { dataStore.data } returns flowOf(prefs)
        val vm = ConvertViewModel(
            currencyService = currencyService,
            cookingService = cookingService,
            conversionHistoryDao = historyDao,
            currencyFavouriteDao = favouriteDao,
            dataStore = dataStore,
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.currencyFavourites.size)
        assertEquals("USD_NZD", vm.uiState.value.currencyFavourites[0].id)
    }
}
