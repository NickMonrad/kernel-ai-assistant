package com.kernel.ai.feature.convert

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ConversionHistoryDao
import com.kernel.ai.core.memory.dao.CurrencyFavouriteDao
import com.kernel.ai.core.memory.entity.ConversionHistoryEntity
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import com.kernel.ai.core.skills.UnitConverter
import com.kernel.ai.core.skills.natives.CookingConversionService
import com.kernel.ai.core.skills.natives.CurrencyConversionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Named

data class ConvertUiState(
    val selectedTab: ConvertTab = ConvertTab.CURRENCY,
    val amount: String = "",
    val fromUnit: String = "USD",
    val toUnit: String = "NZD",
    val toUnitOptions: List<String> = emptyList(),
    val result: ConversionResult? = null,
    val currencyHistory: List<ConversionHistoryEntity> = emptyList(),
    val unitHistory: List<ConversionHistoryEntity> = emptyList(),
    val cookingHistory: List<ConversionHistoryEntity> = emptyList(),
    val currencyFavourites: List<CurrencyFavouriteEntity> = emptyList(),
    val availableCurrencies: List<Pair<String, String>> = emptyList(),
    val cookingIngredient: String = "",
    val cookingIngredients: List<String> = emptyList(),
    val cookingUnits: List<String> = emptyList(),
    val showFavouriteError: Boolean = false,
    val isAustralianTablespoon: Boolean = false,
    val snackbarMessage: String? = null,
    val favouriteRates: Map<String, String> = emptyMap(),
)

@HiltViewModel
class ConvertViewModel @Inject constructor(
    private val currencyService: CurrencyConversionService,
    private val cookingService: CookingConversionService,
    private val conversionHistoryDao: ConversionHistoryDao,
    private val currencyFavouriteDao: CurrencyFavouriteDao,
    @Named("convert") private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConvertUiState())
    val uiState: StateFlow<ConvertUiState> = _uiState.asStateFlow()

    private val KEY_AU_TABLESPOON = booleanPreferencesKey("au_tablespoon")

    private var convertJob: Job? = null
    private var ratesFetchJob: Job? = null

    init {
        // Load cooking units and ingredients eagerly
        val cookingUnits = cookingService.allSupportedUnits().map { it.canonicalName }
        val cookingIngredients = cookingService.allIngredients().map { it.displayName }
        _uiState.update {
            it.copy(cookingUnits = cookingUnits, cookingIngredients = cookingIngredients).withToUnitOptions()
        }

        viewModelScope.launch {
            combine(
                conversionHistoryDao.observeByType("CURRENCY"),
                conversionHistoryDao.observeByType("UNIT"),
                conversionHistoryDao.observeByType("COOKING"),
                currencyFavouriteDao.observeAll(),
            ) { currency, unit, cooking, favs ->
                _uiState.update {
                    it.copy(
                        currencyHistory = currency,
                        unitHistory = unit,
                        cookingHistory = cooking,
                        currencyFavourites = favs,
                    )
                }
            }.collect {}
        }

        // Load DataStore preference
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                _uiState.update { it.copy(isAustralianTablespoon = prefs[KEY_AU_TABLESPOON] ?: false) }
            }
        }

        // Load currencies lazily
        viewModelScope.launch {
            try {
                val currencies = currencyService.getSupportedCurrencies()
                _uiState.update {
                    it.copy(availableCurrencies = currencies.map { (code, resolved) -> code to resolved.displayName })
                }
            } catch (_: Exception) { }
        }
    }

    fun onAmountChanged(text: String) {
        _uiState.update { it.copy(amount = text, result = ConversionResult.Loading) }
        convertJob?.cancel()
        convertJob = viewModelScope.launch {
            delay(300)
            convert()
        }
    }

    fun onTabSelected(tab: ConvertTab) {
        val (from, to) = when (tab) {
            ConvertTab.CURRENCY -> "USD" to "NZD"
            ConvertTab.UNIT -> "km" to "mi"
            ConvertTab.COOKING -> "cup" to "g"
        }
        _uiState.update { it.copy(selectedTab = tab, fromUnit = from, toUnit = to, result = null).withToUnitOptions() }
    }

    fun onFromChanged(value: String) {
        val state = _uiState.value
        if (state.selectedTab == ConvertTab.UNIT) {
            val compatibleUnits = UnitConverter.unitsInSameCategoryAs(value)
            val toUnit = if (state.toUnit in compatibleUnits) state.toUnit else compatibleUnits.firstOrNull() ?: state.toUnit
            _uiState.update { it.copy(fromUnit = value, toUnit = toUnit).withToUnitOptions() }
        } else {
            _uiState.update { it.copy(fromUnit = value) }
        }
        triggerConvert()
    }

    fun onToChanged(value: String) {
        _uiState.update { it.copy(toUnit = value) }
        triggerConvert()
    }

    fun onSwap() {
        _uiState.update { it.copy(fromUnit = it.toUnit, toUnit = it.fromUnit) }
        triggerConvert()
    }

    private fun triggerConvert() {
        convertJob?.cancel()
        convertJob = viewModelScope.launch {
            delay(300)
            convert()
        }
    }

    private suspend fun convert() {
        val state = _uiState.value
        if (state.amount.isBlank()) {
            _uiState.update { it.copy(result = null) }
            return
        }
        _uiState.update { it.copy(result = ConversionResult.Loading) }
        try {
            when (state.selectedTab) {
                ConvertTab.CURRENCY -> {
                    val res = currencyService.convert(state.amount, state.fromUnit, state.toUnit)
                    val formatted = formatCurrency(res.outputAmount)
                    val display = "$formatted ${res.toCurrency.code}"
                    val rateInfo = "1 ${res.fromCurrency.code} = ${formatCurrency(res.rate)} ${res.toCurrency.code} · ${res.rateDate}"
                    _uiState.update { it.copy(result = ConversionResult.Success(display, rateInfo)) }
                    saveHistory("CURRENCY", state.amount, state.fromUnit, state.toUnit, formatted)
                }
                ConvertTab.UNIT -> {
                    val res = UnitConverter.convert(state.amount, state.fromUnit, state.toUnit)
                    val formatted = formatQuantity(res.outputValue)
                    val display = "$formatted ${res.toUnitName}"
                    _uiState.update { it.copy(result = ConversionResult.Success(display)) }
                    saveHistory("UNIT", state.amount, state.fromUnit, state.toUnit, formatted)
                }
                ConvertTab.COOKING -> {
                    fun remapTbsp(unit: String) =
                        if (state.isAustralianTablespoon && unit.equals("tbsp", ignoreCase = true)) "AU tbsp" else unit
                    val res = cookingService.convert(
                        state.amount,
                        remapTbsp(state.fromUnit),
                        state.cookingIngredient,
                        remapTbsp(state.toUnit),
                    )
                    val formatted = formatQuantity(res.outputAmount)
                    val display = "$formatted ${res.toUnit.contentLabel}"
                    val rateInfo = res.assumptionTexts.joinToString("; ").takeIf { it.isNotBlank() }
                    _uiState.update { it.copy(result = ConversionResult.Success(display, rateInfo)) }
                    saveHistory("COOKING", state.amount, res.fromUnit.contentLabel, res.toUnit.contentLabel, formatted)
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(result = ConversionResult.Error(e.message ?: "Conversion failed")) }
        }
    }

    fun addFavourite(fromCode: String, toCode: String) {
        viewModelScope.launch {
            val id = "${fromCode}_${toCode}"
            val inserted = currencyFavouriteDao.insertIfUnderLimit(
                CurrencyFavouriteEntity(id = id, fromCode = fromCode, toCode = toCode, sortOrder = 0),
                limit = 5,
            )
            if (!inserted) {
                _uiState.update { it.copy(showFavouriteError = true) }
            }
        }
    }

    fun removeFavourite(id: String) {
        viewModelScope.launch { currencyFavouriteDao.delete(id) }
    }

    fun clearHistory(tab: ConvertTab) {
        viewModelScope.launch {
            conversionHistoryDao.deleteByType(
                when (tab) {
                    ConvertTab.CURRENCY -> "CURRENCY"
                    ConvertTab.UNIT -> "UNIT"
                    ConvertTab.COOKING -> "COOKING"
                }
            )
        }
    }

    fun dismissFavouriteError() {
        _uiState.update { it.copy(showFavouriteError = false) }
    }

    fun toggleAustralianTablespoon() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_AU_TABLESPOON] = !(prefs[KEY_AU_TABLESPOON] ?: false)
            }
        }
    }

    fun refreshRates() {
        currencyService.evictRatesCache()
        triggerConvert()
    }

    fun onIngredientChanged(value: String) {
        _uiState.update { it.copy(cookingIngredient = value) }
        triggerConvert()
    }

    private suspend fun saveHistory(type: String, input: String, from: String, to: String, output: String) {
        conversionHistoryDao.insert(
            ConversionHistoryEntity(
                type = type,
                inputAmount = input,
                fromLabel = from,
                toLabel = to,
                outputAmount = output,
                createdAt = System.currentTimeMillis(),
            )
        )
        conversionHistoryDao.pruneToLimit(type)
    }

    fun fetchFavouriteRates() {
        ratesFetchJob?.cancel()
        val favs = _uiState.value.currencyFavourites
        ratesFetchJob = viewModelScope.launch {
            val rates = mutableMapOf<String, String>()
            favs.forEach { fav ->
                try {
                    val res = currencyService.convert("1", fav.fromCode, fav.toCode)
                    rates[fav.id] = formatCurrency(res.outputAmount)
                } catch (_: Exception) { }
            }
            _uiState.update { it.copy(favouriteRates = rates) }
        }
    }

    private fun formatCurrency(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun formatQuantity(value: BigDecimal): String {
        if (value.compareTo(BigDecimal.ZERO) == 0) return "0"
        val abs = value.abs()
        val scale = when {
            abs >= BigDecimal.ONE -> 2
            abs >= BigDecimal("0.01") -> 4
            else -> maxOf(4, -abs.scale() + abs.precision() + 2)
        }
        val scaled = value.setScale(scale, RoundingMode.HALF_UP)
        // If rounding killed all significant digits, fall back to 2 sig figs
        if (scaled.compareTo(BigDecimal.ZERO) == 0) {
            return value.round(java.math.MathContext(2, RoundingMode.HALF_UP)).toPlainString()
        }
        return scaled.stripTrailingZeros().toPlainString()
    }
}

private fun ConvertUiState.withToUnitOptions(): ConvertUiState =
    if (selectedTab == ConvertTab.UNIT) copy(toUnitOptions = UnitConverter.unitsInSameCategoryAs(fromUnit))
    else this
