package com.kernel.ai.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.*
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


    var title by remember(note?.id) { mutableStateOf(note?.title ?: "") }
    var content by remember(note?.id) { mutableStateOf(note?.content ?: "") }
    var titleDirty by remember(note?.id) { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val isDirty = title != (note?.title ?: "") || content != (note?.content ?: "")
    BackHandler(enabled = isDirty) {
        showDiscardDialog = true
    }

    LaunchedEffect(note?.title, titleDirty) {
        if (!titleDirty) {
            title = note?.title ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(noteTitle(note)) },
                navigationIcon = {
                    IconButton(onClick = { if (isDirty) showDiscardDialog = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (note != null) {
                        // Pin toggle
                        IconButton(onClick = {
                            if (note.pinned) viewModel.unpinNote(note.id)
                            else viewModel.pinNote(note.id)
                        }) {
                            Icon(
                                if (note.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (note.pinned) "Unpin" else "Pin",
                                tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Archive toggle
                        IconButton(onClick = { showArchiveDialog = true }) {
                            Icon(
                                if (note.archivedAt > 0) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (note.archivedAt > 0) "Unarchive" else "Archive",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Delete
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
                    onValueChange = {
                        title = it
                        titleDirty = true
                    },
                    label = { Text("Title (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Generated automatically when left blank") },
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
                    TextButton(onClick = { if (isDirty) showDiscardDialog = true else onBack() }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {

                            val finalTitle = if (titleDirty) {
                                title.trim().takeIf { it.isNotBlank() }
                            } else {
                                note.title?.takeIf { it.isNotBlank() }
                            }
                            viewModel.updateNote(
                                note.copy(
                                    title = finalTitle,
                                    content = content.trim(),
                                    // updatedAt is set by NotesViewModel.updateNote()
                                ),
                            )
                            onBack()
                        },
                        enabled = content.isNotBlank(),
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

    if (showArchiveDialog && note != null) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(if (note.archivedAt > 0) "Unarchive Note" else "Archive Note") },
            text = { Text(if (note.archivedAt > 0) "Restore this note to your active notes?" else "Archive this note?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (note.archivedAt > 0) viewModel.unarchiveNote(note.id)
                        else viewModel.archiveNote(note.id)
                        showArchiveDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(if (note.archivedAt > 0) "Restore" else "Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Leave without saving?") },
            confirmButton = {
                TextButton(
                    onClick = { showDiscardDialog = false; onBack() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

private fun noteTitle(note: NoteEntity?): String {
    return note?.title?.takeIf { it.isNotBlank() } ?: "New Note"
}
