package com.kernel.ai.feature.settings

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ContactAliasEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactAliasesScreen(
    onBack: () -> Unit = {},
    prefill: ContactAliasPrefill? = null,
    onPrefillConsumed: () -> Unit = {},
    viewModel: ContactAliasesViewModel = hiltViewModel(),
) {
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(prefill?.openAdd == true) }
    // Set after a successful save: offers to open the dialer for the contact (never places a call).
    // Prefill is one-shot: mark consumed so rotation/process death does not reopen the dialog.
    LaunchedEffect(prefill) { if (prefill != null) onPrefillConsumed() }
    var callOffer by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("People & Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add alias")
            }
        },
    ) { innerPadding ->
        if (aliases.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "No aliases yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap + to map a nickname (e.g. \"mum\") to a contact so Jandal can call them by name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = SPELLING_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                item {
                    Text(
                        text = SPELLING_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()
                }
                items(aliases, key = { it.alias }) { entity ->
                    AliasRow(entity = entity, onDelete = { viewModel.deleteAlias(entity.alias) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddAliasDialog(
            initialAlias = prefill?.alias.orEmpty(),
            autoPick = prefill?.openPicker == true,
            suggestedName = prefill?.suggestedName,
            onDismiss = { showAddDialog = false },
            onSave = { alias, displayName, contactId, phoneNumber ->
                viewModel.addAlias(alias, displayName, contactId, phoneNumber)
                showAddDialog = false
                if (prefill?.callRequested == true) callOffer = displayName to phoneNumber
            },
        )
    }

    callOffer?.let { (name, number) ->
        CallOfferDialog(
            name = name,
            phoneNumber = number,
            onDismiss = { callOffer = null },
        )
    }
}

private const val SPELLING_HINT =
    "Add nicknames like \"wife\" or \"Mum\" so you can say \"call Mum\".\n" +
        "Tip: add sound-alike spellings, like \"laurel\" for Laurelle, so Jandal understands how you say names."

@Composable
private fun AliasRow(
    entity: ContactAliasEntity,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text("\"${entity.alias}\" → ${entity.displayName}")
        },
        supportingContent = {
            Text(entity.phoneNumber, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "Delete alias")
            }
        },
    )
}

/**
 * "Call <name> now?" after saving. Confirm opens the dialer with the number; the user still taps
 * call. Jandal never places the call from here.
 */
@Composable
private fun CallOfferDialog(
    name: String,
    phoneNumber: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call $name now?") },
        text = { Text("Opens the dialer with $name's number. You still tap call.") },
        confirmButton = {
            Button(
                onClick = {
                    // No dialer installed or launch refused: the alias is already saved, so ignore.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")),
                        )
                    }
                    onDismiss()
                },
            ) { Text("Open dialer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

@Composable
private fun AddAliasDialog(
    initialAlias: String,
    autoPick: Boolean,
    suggestedName: String?,
    onDismiss: () -> Unit,
    onSave: (alias: String, displayName: String, contactId: String, phoneNumber: String) -> Unit,
) {
    val context = LocalContext.current
    var aliasText by remember { mutableStateOf(initialAlias) }
    var selectedDisplayName by remember { mutableStateOf<String?>(null) }
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var selectedPhoneNumber by remember { mutableStateOf<String?>(null) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact(),
    ) { contactUri ->
        if (contactUri == null) return@rememberLauncherForActivityResult
        // Resolve display name + phone number from the contact URI
        val contactId = ContentUris.parseId(contactUri).toString()
        var displayName: String? = null
        var phoneNumber: String? = null

        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                )
            }
        }

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                phoneNumber = cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                )
            }
        }

        if (displayName != null && phoneNumber != null) {
            selectedDisplayName = displayName
            selectedContactId = contactId
            selectedPhoneNumber = phoneNumber
        }
    }

    // Deep link from a failed lookup: open the picker straight away so the user only confirms.
    LaunchedEffect(autoPick) {
        if (autoPick) contactPickerLauncher.launch(null)
    }

    val canSave = aliasText.isNotBlank() && selectedContactId != null && selectedPhoneNumber != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text("What do you call them?") },
                    placeholder = { Text("e.g. mum, wife, boss") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestedName != null && selectedDisplayName == null) {
                    Text(
                        text = "Pick $suggestedName from your contacts to save this alias.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { contactPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Pick contact")
                }
                if (selectedDisplayName != null) {
                    Text(
                        text = "Selected: $selectedDisplayName (${selectedPhoneNumber})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        aliasText,
                        selectedDisplayName ?: "",
                        selectedContactId ?: "",
                        selectedPhoneNumber ?: "",
                    )
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
