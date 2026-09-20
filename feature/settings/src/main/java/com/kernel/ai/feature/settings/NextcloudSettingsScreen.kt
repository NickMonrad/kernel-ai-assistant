package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: NextcloudSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nextcloud Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Connect one self-managed Nextcloud account. The app password stays in Android Keystore-backed storage and is never uploaded to Jandal.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::setServerUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                singleLine = true,
                placeholder = { Text("https://cloud.example") },
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.appPassword,
                onValueChange = viewModel::setAppPassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = viewModel::connect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text("Test connection and discover task lists") }
            if (state.hasSavedAccount) {
                OutlinedButton(
                    onClick = viewModel::clearAccount,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Forget saved Nextcloud account") }
            }
            if (state.busy) CircularProgressIndicator()
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (state.collections.isNotEmpty()) {
                HorizontalDivider()
                Text("Remote task lists", style = MaterialTheme.typography.titleMedium)
                state.collections.forEach { collection ->
                    OutlinedButton(
                        onClick = { viewModel.importCollection(collection) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import ${collection.displayName}") }
                }
            }
            HorizontalDivider()
            Text("Publish a local list", style = MaterialTheme.typography.titleMedium)
            lists.forEach { list ->
                OutlinedButton(
                    onClick = { viewModel.publishList(list) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Publish ${list.name}") }
            }
            OutlinedButton(
                onClick = viewModel::syncNow,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync bound lists now") }
        }
    }
}
