package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.NoteEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: Long,
    onBack: () -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val note = allNotes.find { it.id == noteId }

    // Sync local edit buffer when the note loads or changes
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(note?.id) {
        note?.let {
            title = it.title
            content = it.content
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(noteTitle(note)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (note != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (note == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Note not found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    minLines = 6,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onBack) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            if (title.isNotBlank()) {
                                viewModel.updateNote(
                                    note.copy(
                                        title = title.trim(),
                                        content = content.trim(),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                onBack()
                            }
                        },
                        enabled = title.isNotBlank(),
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (showDeleteDialog && note != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNote(note)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun noteTitle(note: NoteEntity?): String {
    return note?.title?.takeIf { it.isNotBlank() } ?: "New Note"
}
