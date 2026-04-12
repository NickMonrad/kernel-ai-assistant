package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    viewModel: ModelSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            uiState.e2bSettings?.let { settings ->
                ModelCard(
                    modelName = "Gemma 4 E-2B",
                    settings = settings,
                    onSettingsChanged = viewModel::updateE2bSettings,
                    onReset = viewModel::resetE2bToDefaults,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            uiState.e4bSettings?.let { settings ->
                ModelCard(
                    modelName = "Gemma 4 E-4B",
                    settings = settings,
                    onSettingsChanged = viewModel::updateE4bSettings,
                    onReset = viewModel::resetE4bToDefaults,
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    modelName: String,
    settings: ModelSettingsEntity,
    onSettingsChanged: (ModelSettingsEntity) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = modelName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Context Window
        SliderRow(
            label = "Context window",
            valueLabel = "${settings.contextWindowSize} tokens",
            value = settings.contextWindowSize.toFloat(),
            valueRange = 2048f..32768f,
            steps = ((32768 - 2048) / 1024) - 1,
            isInteger = true,
            onValueChangeFinished = { newVal ->
                val snapped = (newVal / 1024).roundToInt() * 1024
                onSettingsChanged(settings.copy(contextWindowSize = snapped))
            },
        )

        // Temperature
        SliderRow(
            label = "Temperature",
            valueLabel = "%.1f".format(settings.temperature),
            value = settings.temperature,
            valueRange = 0.1f..2.0f,
            steps = 18,
            isInteger = false,
            onValueChangeFinished = { newVal ->
                onSettingsChanged(settings.copy(temperature = (newVal * 10).roundToInt() / 10f))
            },
        )

        // Top-P
        SliderRow(
            label = "Top-P",
            valueLabel = "%.2f".format(settings.topP),
            value = settings.topP,
            valueRange = 0.0f..1.0f,
            steps = 19,
            isInteger = false,
            onValueChangeFinished = { newVal ->
                onSettingsChanged(settings.copy(topP = (newVal * 20).roundToInt() / 20f))
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to defaults")
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    isInteger: Boolean = false,
    onValueChangeFinished: (Float) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    // Text field tracks what the user types; synced when slider moves or on commit
    var textValue by remember(value) {
        mutableStateOf(if (isInteger) value.roundToInt().toString() else "%.2f".format(value))
    }

    fun commitText(raw: String) {
        val parsed = if (isInteger) raw.trim().toIntOrNull()?.toFloat() else raw.trim().toFloatOrNull()
        if (parsed != null) {
            val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
            sliderValue = clamped
            textValue = if (isInteger) clamped.roundToInt().toString() else "%.2f".format(clamped)
            onValueChangeFinished(clamped)
        } else {
            // Revert to current slider value on invalid input
            textValue = if (isInteger) sliderValue.roundToInt().toString() else "%.2f".format(sliderValue)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { newVal ->
                    sliderValue = newVal
                    textValue = if (isInteger) newVal.roundToInt().toString() else "%.2f".format(newVal)
                },
                onValueChangeFinished = { onValueChangeFinished(sliderValue) },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier
                    .width(76.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) commitText(textValue)
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isInteger) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(textValue)
                        focusManager.clearFocus()
                    },
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelSettingsScreenPreview() {
    MaterialTheme {
        val sampleSettings = ModelSettingsEntity(
            modelId = "gemma_4_e2b",
            contextWindowSize = 4096,
            temperature = 1.0f,
            topP = 0.95f,
        )
        ModelCard(
            modelName = "Gemma 4 E-2B",
            settings = sampleSettings,
            onSettingsChanged = {},
            onReset = {},
        )
    }
}
