@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.nextcloud.NextcloudShare
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission

@Composable
fun NextcloudSharingScreen(
    collectionId: String,
    onBack: () -> Unit = {},
    viewModel: NextcloudSharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRemove by remember { mutableStateOf<NextcloudShare?>(null) }

    LaunchedEffect(collectionId) { viewModel.start(collectionId) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFeedback()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Nextcloud sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading,
                        modifier = Modifier.testTag("nextcloud_sharing_refresh"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh sharing")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NextcloudSharingContent(
            state = state,
            modifier = Modifier.padding(padding),
            onQueryChange = viewModel::setQuery,
            onSelectSharee = viewModel::selectSharee,
            onSelectPermission = viewModel::selectPermission,
            onShareSelected = viewModel::shareSelected,
            onChangePermission = viewModel::changePermission,
            onRemove = { pendingRemove = it },
        )
    }

    pendingRemove?.let { share ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove sharing?") },
            text = { Text("${share.displayName} will no longer have access to this list.") },
            confirmButton = {
                TextButton(
                    onClick = { pendingRemove = null; viewModel.remove(share) },
                    modifier = Modifier.testTag("nextcloud_sharing_remove_confirm"),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun NextcloudSharingContent(
    state: NextcloudSharingState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    onSelectSharee: (com.kernel.ai.core.memory.nextcloud.NextcloudSharee) -> Unit = {},
    onSelectPermission: (NextcloudSharePermission) -> Unit = {},
    onShareSelected: () -> Unit = {},
    onChangePermission: (NextcloudShare, NextcloudSharePermission) -> Unit = { _, _ -> },
    onRemove: (NextcloudShare) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Manage who can access this bound Nextcloud task list. Nextcloud remains authoritative.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.testTag("nextcloud_sharing_loading"))
            state.error != null -> Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("nextcloud_sharing_error"),
            )
            else -> {
                if (!state.writable) {
                    Text(
                        "This list is read-only for this account. Sharing changes are unavailable.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("nextcloud_sharing_read_only"),
                    )
                } else {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nextcloud_sharing_search"),
                        label = { Text("Search users or groups") },
                        singleLine = true,
                    )
                    if (state.searching) CircularProgressIndicator()
                    state.results.forEach { sharee ->
                        val selected = state.selectedSharee?.principal == sharee.principal
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = state.busyPrincipal == null) {
                                    onSelectSharee(sharee)
                                }
                                .testTag("nextcloud_sharing_result"),
                            leadingContent = {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onSelectSharee(sharee) },
                                    enabled = state.busyPrincipal == null,
                                )
                            },
                            headlineContent = { Text(sharee.displayName) },
                            supportingContent = { Text(sharee.typeLabel()) },
                        )
                        HorizontalDivider()
                    }
                    state.selectedSharee?.let { selected ->
                        Text(
                            "Selected: ${selected.displayName} (${selected.typeLabel()})",
                            modifier = Modifier.testTag("nextcloud_sharing_selected"),
                        )
                        Text("Permission", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onSelectPermission(NextcloudSharePermission.READ_ONLY)
                                },
                                modifier = Modifier.testTag("nextcloud_sharing_permission_read"),
                                enabled = state.busyPrincipal == null,
                            ) {
                                Text(
                                    "Read-only" +
                                        if (state.selectedPermission == NextcloudSharePermission.READ_ONLY) {
                                            " ✓"
                                        } else {
                                            ""
                                        },
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    onSelectPermission(NextcloudSharePermission.EDITABLE)
                                },
                                modifier = Modifier.testTag("nextcloud_sharing_permission_edit"),
                                enabled = state.busyPrincipal == null,
                            ) {
                                Text(
                                    "Editable" +
                                        if (state.selectedPermission == NextcloudSharePermission.EDITABLE) {
                                            " ✓"
                                        } else {
                                            ""
                                        },
                                )
                            }
                        }
                        Button(
                            onClick = onShareSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nextcloud_sharing_share"),
                            enabled = state.busyPrincipal == null,
                        ) {
                            Text("Share")
                        }
                    }

                }
                Text(
                    "People and groups with access",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("nextcloud_sharing_list_heading"),
                )
                if (state.shares.isEmpty()) {
                    Text("No shares yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    state.shares.forEach { share ->
                        ListItem(
                            headlineContent = { Text(share.displayName) },
                            supportingContent = {
                                Text(
                                    "${share.type.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                                        share.permissionLabel() +
                                        if (share.invitationAccepted) "" else " · Invitation pending",
                                )
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    onChangePermission(
                                        share,
                                        if (share.permission == NextcloudSharePermission.EDITABLE) {
                                            NextcloudSharePermission.READ_ONLY
                                        } else {
                                            NextcloudSharePermission.EDITABLE
                                        },
                                    )
                                },
                                enabled = state.busyPrincipal == null && state.writable,
                                modifier = Modifier.testTag("nextcloud_sharing_permission"),
                            ) {
                                Text(
                                    if (share.permission == NextcloudSharePermission.EDITABLE) {
                                        "Make read-only"
                                    } else {
                                        "Make editable"
                                    },
                                )
                            }
                            TextButton(
                                onClick = { onRemove(share) },
                                enabled = state.busyPrincipal == null && state.writable,
                                modifier = Modifier.testTag("nextcloud_sharing_remove"),
                            ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
