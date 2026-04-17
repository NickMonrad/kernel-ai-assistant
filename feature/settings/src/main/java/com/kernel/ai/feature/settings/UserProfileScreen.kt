package com.kernel.ai.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.profile.UserProfileYaml

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val savedProfile by viewModel.profileText.collectAsStateWithLifecycle()
    val structured by viewModel.structuredProfile.collectAsStateWithLifecycle()

    // Keep the last non-null structured value so StructuredProfileCard has a safe
    // reference during AnimatedVisibility's exit animation (prevents NPE when clear() fires).
    var lastStructured by remember { mutableStateOf<UserProfileYaml?>(null) }
    if (structured != null) lastStructured = structured

    // Local edit buffer — initialised from saved value.
    var editText by rememberSaveable(savedProfile) { mutableStateOf(savedProfile) }
    val isDirty = editText != savedProfile
    val charCount = editText.length
    val maxLength = viewModel.maxLength

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                text = "Tell Jandal about yourself. This is injected into every conversation so Jandal always has context about you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = editText,
                onValueChange = { if (it.length <= maxLength) editText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                label = { Text("Profile") },
                placeholder = {
                    Text("e.g. My name is Nick. I use a Samsung S23 Ultra. I prefer concise answers.")
                },
                supportingText = {
                    Text(
                        text = "$charCount / $maxLength characters",
                        color = if (charCount > maxLength * 0.9) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                minLines = 6,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        editText = ""
                        viewModel.clear()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }

                Button(
                    onClick = { viewModel.save(editText) },
                    modifier = Modifier.weight(1f),
                    enabled = isDirty,
                ) {
                    Text("Save")
                }
            }

            // Structured preview — shows parsed fields from the saved profile
            AnimatedVisibility(visible = structured?.isEmpty() == false) {
                lastStructured?.let { StructuredProfileCard(profile = it) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StructuredProfileCard(profile: UserProfileYaml) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Parsed Profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            profile.name?.let { FieldRow("Name", it) }
            profile.role?.let { FieldRow("Role", it) }
            profile.location?.let { FieldRow("Location", it) }

            if (profile.environment.isNotEmpty()) {
                FieldList("Environment", profile.environment)
            }
            if (profile.context.isNotEmpty()) {
                FieldList("Context", profile.context)
            }
            if (profile.rules.isNotEmpty()) {
                FieldList("Preferences", profile.rules)
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FieldList(label: String, items: List<String>) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { item ->
            Text(
                text = "  • $item",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
