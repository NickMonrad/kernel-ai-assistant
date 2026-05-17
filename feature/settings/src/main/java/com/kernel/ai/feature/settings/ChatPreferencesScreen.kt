package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val RETENTION_OPTIONS = listOf(
    1 to "1 day",
    3 to "3 days",
    7 to "7 days",
    14 to "14 days",
    30 to "30 days",
    -1 to "Never",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPreferencesScreen(
    onBack: () -> Unit = {},
    viewModel: ChatPreferencesViewModel = hiltViewModel(),
) {
    val retentionDays by viewModel.archiveRetentionDays.collectAsStateWithLifecycle()
    var showRetentionPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Preferences") },
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
                .padding(innerPadding),
        ) {
            Text(
                text = "Archive",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val currentLabel = RETENTION_OPTIONS.find { it.first == retentionDays }?.second ?: "7 days"
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRetentionPicker = true },
                headlineContent = { Text("Auto-delete archived after") },
                supportingContent = { Text(currentLabel) },
            )
            HorizontalDivider()
        }
    }

    if (showRetentionPicker) {
        AlertDialog(
            onDismissRequest = { showRetentionPicker = false },
            title = { Text("Auto-delete archived after") },
            text = {
                Column {
                    RETENTION_OPTIONS.forEach { (days, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = days == retentionDays,
                                    onClick = {
                                        scope.launch { viewModel.setArchiveRetentionDays(days) }
                                        showRetentionPicker = false
                                    },
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { viewModel.setArchiveRetentionDays(days) }
                                    showRetentionPicker = false
                                },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRetentionPicker = false }) { Text("Cancel") }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatPreferencesScreenPreview() {
    MaterialTheme {
        ChatPreferencesScreen()
    }
}
