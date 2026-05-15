package com.kernel.ai.feature.convert

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ConversionHistoryEntity
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import com.kernel.ai.core.skills.UnitConverter
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onBack: () -> Unit = {},
    onNavigateToVoiceActions: () -> Unit = {},
    viewModel: ConvertViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.showFavouriteError) {
        if (uiState.showFavouriteError) {
            snackbarHostState.showSnackbar("Maximum 5 favourites allowed")
            viewModel.dismissFavouriteError()
        }
    }

    LaunchedEffect(uiState.currencyFavourites) {
        if (uiState.currencyFavourites.isNotEmpty()) {
            viewModel.fetchFavouriteRates()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convert") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            if (uiState.selectedTab == ConvertTab.COOKING) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = uiState.isAustralianTablespoon,
                                                onCheckedChange = null,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Australian tablespoon (20ml)")
                                        }
                                    },
                                    onClick = {
                                        viewModel.toggleAustralianTablespoon()
                                        showMenu = false
                                    },
                                )
                            }
                            if (uiState.selectedTab == ConvertTab.CURRENCY) {
                                DropdownMenuItem(
                                    text = { Text("Refresh currency rates") },
                                    onClick = {
                                        viewModel.refreshRates()
                                        showMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                onClick = {
                                    viewModel.clearHistory(uiState.selectedTab)
                                    showMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToVoiceActions,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice input")
            }
        },
    ) { padding ->
        Column(modifier = Modifier
            .padding(padding)
            .fillMaxSize()) {
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                ConvertTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.onTabSelected(tab) },
                        text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                when (uiState.selectedTab) {
                    ConvertTab.CURRENCY -> {
                        if (uiState.currencyFavourites.isNotEmpty()) {
                            item {
                                FavouriteChipsRow(
                                    favourites = uiState.currencyFavourites,
                                    favouriteRates = uiState.favouriteRates,
                                    onChipClick = { fav ->
                                        viewModel.onFromChanged(fav.fromCode)
                                        viewModel.onToChanged(fav.toCode)
                                    },
                                    onRemove = { viewModel.removeFavourite(it.id) },
                                )
                            }
                        }
                        item {
                            ConversionInputSection(
                                amount = uiState.amount,
                                fromUnit = uiState.fromUnit,
                                toUnit = uiState.toUnit,
                                onAmountChanged = viewModel::onAmountChanged,
                                onSwap = viewModel::onSwap,
                                onFromChanged = viewModel::onFromChanged,
                                onToChanged = viewModel::onToChanged,
                                pickerOptions = uiState.availableCurrencies.map { it.first },
                            )
                        }
                        item {
                            AnimatedResultDisplay(
                                result = uiState.result,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (uiState.result is ConversionResult.Success) {
                            item {
                                TextButton(
                                    onClick = { viewModel.addFavourite(uiState.fromUnit, uiState.toUnit) }
                                ) {
                                    Text("★ Save ${uiState.fromUnit}→${uiState.toUnit} as favourite")
                                }
                            }
                        }
                        items(uiState.currencyHistory) { entry ->
                            HistoryCard(entry)
                        }
                    }

                    ConvertTab.UNIT -> {
                        item {
                            ConversionInputSection(
                                amount = uiState.amount,
                                fromUnit = uiState.fromUnit,
                                toUnit = uiState.toUnit,
                                onAmountChanged = viewModel::onAmountChanged,
                                onSwap = viewModel::onSwap,
                                onFromChanged = viewModel::onFromChanged,
                                onToChanged = viewModel::onToChanged,
                                pickerOptions = UnitConverter.supportedUnits(),
                                toPickerOptions = uiState.toUnitOptions.ifEmpty { UnitConverter.supportedUnits() },
                            )
                        }
                        item {
                            AnimatedResultDisplay(
                                result = uiState.result,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        items(uiState.unitHistory) { entry ->
                            HistoryCard(entry)
                        }
                    }

                    ConvertTab.COOKING -> {
                        item {
                            ConversionInputSection(
                                amount = uiState.amount,
                                fromUnit = uiState.fromUnit,
                                toUnit = uiState.toUnit,
                                onAmountChanged = viewModel::onAmountChanged,
                                onSwap = viewModel::onSwap,
                                onFromChanged = viewModel::onFromChanged,
                                onToChanged = viewModel::onToChanged,
                                pickerOptions = uiState.cookingUnits,
                            )
                        }
                        item {
                            IngredientPickerButton(
                                selected = uiState.cookingIngredient,
                                ingredients = uiState.cookingIngredients,
                                onSelect = viewModel::onIngredientChanged,
                            )
                        }
                        item {
                            AnimatedResultDisplay(
                                result = uiState.result,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        items(uiState.cookingHistory) { entry ->
                            HistoryCard(entry)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversionInputSection(
    amount: String,
    fromUnit: String,
    toUnit: String,
    onAmountChanged: (String) -> Unit,
    onSwap: () -> Unit,
    onFromChanged: (String) -> Unit,
    onToChanged: (String) -> Unit,
    pickerOptions: List<String>,
    toPickerOptions: List<String> = pickerOptions,
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var fromSearch by remember { mutableStateOf("") }
    var toSearch by remember { mutableStateOf("") }
    val fromSheetState = rememberModalBottomSheetState()
    val toSheetState = rememberModalBottomSheetState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChanged,
            label = { Text("Amount") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { fromSearch = ""; showFromPicker = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(fromUnit, maxLines = 1)
            }
            IconButton(onClick = onSwap) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Swap")
            }
            OutlinedButton(
                onClick = { toSearch = ""; showToPicker = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(toUnit, maxLines = 1)
            }
        }
    }

    if (showFromPicker) {
        UnitPickerBottomSheet(
            sheetState = fromSheetState,
            options = pickerOptions,
            search = fromSearch,
            onSearchChange = { fromSearch = it },
            onSelect = { onFromChanged(it); showFromPicker = false },
            onDismiss = { showFromPicker = false },
        )
    }

    if (showToPicker) {
        UnitPickerBottomSheet(
            sheetState = toSheetState,
            options = toPickerOptions,
            search = toSearch,
            onSearchChange = { toSearch = it },
            onSelect = { onToChanged(it); showToPicker = false },
            onDismiss = { showToPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientPickerButton(
    selected: String,
    ingredients: List<String>,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    OutlinedButton(
        onClick = { showPicker = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (selected.isBlank()) "Ingredient (optional, for density conversion)" else selected)
    }

    if (showPicker) {
        UnitPickerBottomSheet(
            sheetState = sheetState,
            options = ingredients,
            search = search,
            onSearchChange = { search = it },
            onSelect = {
                onSelect(it)
                showPicker = false
                search = ""
            },
            onDismiss = {
                showPicker = false
                search = ""
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPickerBottomSheet(
    sheetState: SheetState,
    options: List<String>,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = options.filter { it.contains(search, ignoreCase = true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn {
            items(filtered) { option ->
                ListItem(
                    headlineContent = { Text(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FavouriteChipsRow(
    favourites: List<CurrencyFavouriteEntity>,
    favouriteRates: Map<String, String>,
    onChipClick: (CurrencyFavouriteEntity) -> Unit,
    onRemove: (CurrencyFavouriteEntity) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        favourites.forEach { fav ->
            val rate = favouriteRates[fav.id]
            InputChip(
                selected = false,
                onClick = { onChipClick(fav) },
                label = {
                    Text("${fav.fromCode}→${fav.toCode}${if (rate != null) "  $rate" else ""}")
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove favourite",
                        modifier = Modifier.clickable { onRemove(fav) },
                    )
                },
            )
        }
    }
}

@Composable
private fun HistoryCard(entity: ConversionHistoryEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text("${entity.inputAmount} ${entity.fromLabel} → ${entity.outputAmount} ${entity.toLabel}")
            },
            supportingContent = {
                val time = Instant.ofEpochMilli(entity.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                Text("${time.toLocalDate()} ${time.toLocalTime().withSecond(0).withNano(0)}")
            },
        )
    }
}
