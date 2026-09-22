package com.kernel.ai.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.nextcloud.NextcloudListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudSettingsScreen(
    onBack: () -> Unit = {},
    onSetupCompleted: () -> Unit = {},
    pendingListId: Long? = null,
    pendingListName: String? = null,
    viewModel: NextcloudSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingListId, pendingListName) {
        viewModel.setPendingList(pendingListId, pendingListName)
    }
    // A contextual first-time setup hands the user straight back to the list that asked for it.
    LaunchedEffect(state.pendingSetupCompleted) {
        if (state.pendingSetupCompleted) {
            onSetupCompleted()
            viewModel.consumePendingSetupCompleted()
        }
    }
    LaunchedEffect(state.feedback, state.pendingSetupCompleted) {
        val feedback = state.feedback
        if (feedback != null && !state.pendingSetupCompleted) {
            snackbarHostState.showSnackbar(feedback)
            viewModel.consumeFeedback()
        }
    }

    NextcloudListsContent(
        state = state,
        sections = sections,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAddressChange = viewModel::setAddress,
        onUsernameChange = viewModel::setUsername,
        onAppPasswordChange = viewModel::setAppPassword,
        onAllowInsecureHttpChange = viewModel::setAllowInsecureHttp,
        onConnect = viewModel::connect,
        onEditAccount = viewModel::editAccount,
        onCancelEditing = viewModel::cancelEditing,
        onDisconnect = viewModel::disconnect,
        onRefresh = viewModel::refresh,
        onQueryChange = viewModel::setQuery,
        onFilterChange = viewModel::setFilter,
        onAddToJandal = viewModel::addToJandal,
        onSyncWithNextcloud = viewModel::syncWithNextcloud,
        onStopSync = viewModel::stopSync,
        onResumeSync = viewModel::resumeSync,
        onRetry = viewModel::retry,
    )
}

/** Stateless screen body, so the user-visible contract can be exercised without a network. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextcloudListsContent(
    state: NextcloudSettingsState,
    sections: NextcloudListSections,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit = {},
    onAddressChange: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    onAppPasswordChange: (String) -> Unit = {},
    onAllowInsecureHttpChange: (Boolean) -> Unit = {},
    onConnect: () -> Unit = {},
    onEditAccount: () -> Unit = {},
    onCancelEditing: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (NextcloudListFilter) -> Unit = {},
    onAddToJandal: (NextcloudListItem) -> Unit = {},
    onSyncWithNextcloud: (NextcloudListItem) -> Unit = {},
    onStopSync: (NextcloudListItem) -> Unit = {},
    onResumeSync: (NextcloudListItem) -> Unit = {},
    onRetry: (NextcloudListItem) -> Unit = {},
) {
    var pendingStop by remember { mutableStateOf<NextcloudListItem?>(null) }
    val connected = state.connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nextcloud lists") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (connected != null) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("nextcloud_refresh"),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Nextcloud lists")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                connected == null || state.editingAccount -> CredentialEntry(
                    state = state,
                    onAddressChange = onAddressChange,
                    onUsernameChange = onUsernameChange,
                    onAppPasswordChange = onAppPasswordChange,
                    onAllowInsecureHttpChange = onAllowInsecureHttpChange,
                    onConnect = onConnect,
                    onCancelEditing = onCancelEditing,
                )
                state.authenticationFailed -> AccountNeedsAttention(
                    onReconnect = onEditAccount,
                    onDisconnect = onDisconnect,
                )
                else -> ConnectedAccountSummary(
                    server = connected.serverUrl,
                    username = connected.username,
                    onEdit = onEditAccount,
                    onDisconnect = onDisconnect,
                )
            }

            if (connected != null && !state.editingAccount && !state.authenticationFailed) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nextcloud_search_field"),
                    placeholder = { Text("Search lists") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NextcloudListFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("nextcloud_filter_${filter.name}"),
                        )
                    }
                }
                if (sections.isEmpty) {
                    Text(
                        text = when {
                            state.query.isNotBlank() -> "No lists match \"${state.query}\"."
                            state.filter != NextcloudListFilter.ALL -> "No lists in ${state.filter.label}."
                            else -> "No lists found."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sections.connected.isNotEmpty()) {
                    NextcloudSectionHeader("connected", NextcloudListSection.CONNECTED.title)
                    sections.connected.forEach { item ->
                        ConnectedListRow(
                            item = item,
                            busyCollectionId = state.busyCollectionId,
                            onClick = onRefresh,
                            onStopSync = { pendingStop = item },
                            onResumeSync = { onResumeSync(item) },
                            onRetry = { onRetry(item) },
                        )
                    }
                }
                if (sections.nextcloudOnly.isNotEmpty()) {
                    NextcloudSectionHeader("nextcloud_only", NextcloudListSection.NEXTCLOUD_ONLY.title)
                    sections.nextcloudOnly.forEach { item ->
                        UnboundListRow(item, actionLabel = "Add to Jandal") { onAddToJandal(item) }
                    }
                }
                if (sections.jandalOnly.isNotEmpty()) {
                    NextcloudSectionHeader("jandal_only", NextcloudListSection.JANDAL_ONLY.title)
                    sections.jandalOnly.forEach { item ->
                        UnboundListRow(item, actionLabel = "Sync with Nextcloud") { onSyncWithNextcloud(item) }
                    }
                }
            }

            if (state.busy && state.busyCollectionId == null) {
                CircularProgressIndicator()
            }
        }
    }

    pendingStop?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingStop = null },
            title = { Text("Stop syncing \u201C${item.displayName}\u201D with Nextcloud?") },
            text = {
                Text(
                    "The list will remain in Jandal and the existing copy will remain in Nextcloud. " +
                        "Changes will no longer sync between them.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingStop = null; onStopSync(item) },
                    modifier = Modifier.testTag("nextcloud_stop_confirm"),
                ) { Text("Stop syncing") }
            },
            dismissButton = {
                TextButton(onClick = { pendingStop = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CredentialEntry(
    state: NextcloudSettingsState,
    onAddressChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAppPasswordChange: (String) -> Unit,
    onAllowInsecureHttpChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onCancelEditing: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect Nextcloud", style = MaterialTheme.typography.titleMedium)
        val pendingName = state.pendingListName
        Text(
            text = if (pendingName == null) {
                "One self-managed Nextcloud account syncs the lists you choose. The app password " +
                    "stays in Android Keystore-backed storage."
            } else {
                "Connect a Nextcloud account to sync \u201C$pendingName\u201D."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nextcloud_address_field"),
            label = { Text("Nextcloud address") },
            placeholder = { Text("cloud.example.com") },
            singleLine = true,
            isError = state.addressError != null,
            supportingText = state.addressError?.let { { Text(it) } },
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nextcloud_username_field"),
            label = { Text("Username") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.appPassword,
            onValueChange = onAppPasswordChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nextcloud_app_password_field"),
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.allowInsecureHttp,
                onCheckedChange = onAllowInsecureHttpChange,
                modifier = Modifier.testTag("nextcloud_insecure_http_toggle"),
            )
            Text("Use insecure HTTP")
        }
        if (state.allowInsecureHttp) {
            Text(
                text = "Credentials and list traffic will not be encrypted. Use this only when the " +
                    "server cannot use HTTPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "HTTPS is used unless you enable insecure HTTP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.connected != null) {
                OutlinedButton(onClick = onCancelEditing) { Text("Cancel") }
            }
            Button(
                onClick = onConnect,
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                modifier = Modifier.testTag("nextcloud_connect_button"),
            ) { Text("Connect") }
        }
    }
}

@Composable
private fun ConnectedAccountSummary(
    server: String,
    username: String,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("nextcloud_account_summary"),
    ) {
        Text("Connected to $server", style = MaterialTheme.typography.titleMedium)
        Text("as $username", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("nextcloud_edit_account")) {
                Text("Edit account")
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("nextcloud_disconnect")) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun AccountNeedsAttention(onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("nextcloud_needs_attention"),
    ) {
        Text("Nextcloud connection needs attention", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your Jandal lists are still available on this device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReconnect, modifier = Modifier.testTag("nextcloud_reconnect")) {
                Text("Reconnect")
            }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun NextcloudSectionHeader(tag: String, label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("nextcloud_section_$tag"),
    )
}

@Composable
private fun ConnectedListRow(
    item: NextcloudListItem,
    busyCollectionId: String?,
    onClick: () -> Unit,
    onStopSync: () -> Unit,
    onResumeSync: () -> Unit,
    onRetry: () -> Unit,
) {
    var showOverflow by remember { mutableStateOf(false) }
    val syncState = item.syncState ?: NextcloudListState.UP_TO_DATE
    val busy = item.collectionId != null && item.collectionId == busyCollectionId
    ListItem(
        headlineContent = { Text(item.displayName) },
        supportingContent = { Text(syncState.label()) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (syncState == NextcloudListState.NEEDS_ATTENTION) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("nextcloud_retry_${item.key}"),
                    ) { Text("Retry") }
                }
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Icon(
                        imageVector = syncState.icon(),
                        contentDescription = syncState.contentDescription(),
                        tint = syncState.tint(),
                    )
                }
                Box {
                    IconButton(
                        onClick = { showOverflow = true },
                        modifier = Modifier.testTag("nextcloud_overflow_${item.key}"),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        if (syncState == NextcloudListState.SYNC_OFF) {
                            DropdownMenuItem(
                                text = { Text("Resume Nextcloud sync") },
                                onClick = { showOverflow = false; onResumeSync() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Stop Nextcloud sync") },
                                onClick = { showOverflow = false; onStopSync() },
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.testTag("nextcloud_row_${item.key}"),
    )
    HorizontalDivider()
}

@Composable
private fun UnboundListRow(item: NextcloudListItem, actionLabel: String, onAction: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.displayName) },
        supportingContent = {
            Text(
                if (item.section == NextcloudListSection.NEXTCLOUD_ONLY) {
                    "Not in Jandal"
                } else {
                    "Only on this device"
                },
            )
        },
        trailingContent = {
            TextButton(
                onClick = onAction,
                modifier = Modifier.testTag("nextcloud_action_${item.key}"),
            ) { Text(actionLabel) }
        },
        modifier = Modifier.testTag("nextcloud_row_${item.key}"),
    )
    HorizontalDivider()
}

internal fun NextcloudListState.label(): String = when (this) {
    NextcloudListState.UP_TO_DATE -> "Up to date"
    NextcloudListState.SYNCING -> "Syncing\u2026"
    NextcloudListState.SYNC_OFF -> "Sync off"
    NextcloudListState.NEEDS_ATTENTION -> "Needs attention"
}

internal fun NextcloudListState.icon() = when (this) {
    NextcloudListState.UP_TO_DATE -> Icons.Default.Cloud
    NextcloudListState.SYNCING -> Icons.Default.Sync
    NextcloudListState.SYNC_OFF -> Icons.Default.CloudOff
    NextcloudListState.NEEDS_ATTENTION -> Icons.Default.WarningAmber
}

internal fun NextcloudListState.contentDescription(): String = when (this) {
    NextcloudListState.UP_TO_DATE -> "Nextcloud sync up to date"
    NextcloudListState.SYNCING -> "Syncing with Nextcloud"
    NextcloudListState.SYNC_OFF -> "Nextcloud sync stopped"
    NextcloudListState.NEEDS_ATTENTION -> "Nextcloud sync needs attention"
}

@Composable
internal fun NextcloudListState.tint() = when (this) {
    NextcloudListState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
    NextcloudListState.SYNC_OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}
