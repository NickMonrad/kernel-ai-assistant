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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val savedProfile by viewModel.profileText.collectAsStateWithLifecycle()
    val structured by viewModel.structuredProfile.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    // Keep the last non-null structured value so StructuredProfileCard has a safe
    // reference during AnimatedVisibility's exit animation (prevents NPE when clear() fires).
    var lastStructured by remember { mutableStateOf<UserProfileYaml?>(null) }
    if (structured != null) lastStructured = structured

    // Local edit buffer — stable key, synced safely below to avoid mid-save overwrite.
    var editText by rememberSaveable { mutableStateOf("") }

    // Snapshot of editText when save was initiated, used for divergence detection.
    // When a save completes, if editText has diverged (user kept typing), keep their edits.
    var savedTextSnapshot by remember { mutableStateOf<String?>(null) }

    // Sync editText from savedProfile when the screen first loads or when the repository
    // changes externally (e.g. clear from another session). Skip during save to avoid
    // overwriting edits made while a save is in flight.
    LaunchedEffect(savedProfile, saving) {
        if (!saving && savedTextSnapshot == null) {
            editText = savedProfile
        }
    }

    // Save lifecycle tracking: capture snapshot on save start, handle divergence on completion.
    LaunchedEffect(saving) {
        if (saving) {
            savedTextSnapshot = editText
        } else if (savedTextSnapshot != null) {
            // Save completed — only sync if user hasn't edited since save was clicked
            if (editText == savedTextSnapshot) {
                editText = savedProfile
            }
            savedTextSnapshot = null
        }
    }

    // Inline save status text below the buttons for transient UX feedback.
    // Avoids SnackbarHost for simpler UX — SnackbarHost was not the source of
    // the PopupLayout retention; the trigger was enabled = !saving on the text field.
    var saveStatusText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.saveResult.collect { result ->
            saveStatusText = when (result) {
                is SaveResult.Success -> "Profile saved"
                is SaveResult.Error -> "Save failed: ${result.message}"
            }
            delay(3000)
            saveStatusText = null
        }
    }

    val isDirty = editText != savedProfile && !saving
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
        },
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
                readOnly = saving,
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
                    enabled = !saving,
                ) {
                    Text("Clear")
                }

                Button(
                    onClick = { viewModel.save(editText) },
                    modifier = Modifier.weight(1f),
                    enabled = isDirty && !saving,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (saving) {
                        Text("Saving…")
                    } else {
                        Text("Save")
                    }
                }
            }

            // Inline save status — replaces SnackbarHost to avoid PopupLayout leak
            if (saveStatusText != null) {
                Text(
                    text = saveStatusText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveStatusText!!.startsWith("Save failed"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Parsed Profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            profile.name?.let {
                FieldRow(label = "Name", value = it)
            }
            profile.role?.let {
                FieldRow(label = "Role", value = it)
            }
            profile.location?.let {
                FieldRow(label = "Location", value = it)
            }

            if (profile.environment.isNotEmpty()) {
                FieldList(label = "Environment", items = profile.environment)
            }
            if (profile.context.isNotEmpty()) {
                FieldList(label = "Context", items = profile.context)
            }
            if (profile.rules.isNotEmpty()) {
                FieldList(label = "Rules", items = profile.rules)
            }
            if (profile.facts.isNotEmpty()) {
                FieldList(label = "Facts", items = profile.facts)
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row {
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
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    items.forEach { item ->
        Text(
            text = "  \u2022 $item",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
