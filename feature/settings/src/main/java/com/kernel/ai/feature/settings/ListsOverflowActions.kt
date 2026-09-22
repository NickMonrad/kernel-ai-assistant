package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.memory.nextcloud.NextcloudListState

/**
 * Per-list Nextcloud actions for a list overflow menu (#1551).
 *
 * [state] is null for a list that has never been connected, which is also the only case where
 * `Sync with Nextcloud` is offered. When no account is configured the caller still shows that
 * action and routes to Nextcloud setup afterwards rather than hiding the capability.
 */
internal data class NextcloudRowActions(
    val state: NextcloudListState?,
    val onSyncWithNextcloud: () -> Unit = {},
    val onStopSync: () -> Unit = {},
    val onResumeSync: () -> Unit = {},
    val onOpenNextcloud: () -> Unit = {},
)

/**
 * The state-aware Nextcloud entries shared by the Lists overview row overflow and the list detail
 * overflow, so both surfaces offer exactly the same lifecycle:
 *
 * - never connected → `Sync with Nextcloud`
 * - connected and syncing → `Stop Nextcloud sync`
 * - connected with sync stopped → `Resume Nextcloud sync`
 * - connected either way → `Nextcloud`, opening the existing binding state
 */
@Composable
internal fun NextcloudOverflowItems(
    actions: NextcloudRowActions,
    onDismiss: () -> Unit,
    testTagPrefix: String = "lists_row",
) {
    when (actions.state) {
        null -> DropdownMenuItem(
            text = { Text("Sync with Nextcloud") },
            onClick = { onDismiss(); actions.onSyncWithNextcloud() },
            modifier = Modifier.testTag("${testTagPrefix}_sync_with_nextcloud"),
        )
        NextcloudListState.SYNC_OFF -> DropdownMenuItem(
            text = { Text("Resume Nextcloud sync") },
            onClick = { onDismiss(); actions.onResumeSync() },
            modifier = Modifier.testTag("${testTagPrefix}_resume_nextcloud_sync"),
        )
        else -> DropdownMenuItem(
            text = { Text("Stop Nextcloud sync") },
            onClick = { onDismiss(); actions.onStopSync() },
            modifier = Modifier.testTag("${testTagPrefix}_stop_nextcloud_sync"),
        )
    }
    if (actions.state != null) {
        DropdownMenuItem(
            text = { Text("Nextcloud") },
            onClick = { onDismiss(); actions.onOpenNextcloud() },
            modifier = Modifier.testTag("${testTagPrefix}_open_nextcloud"),
        )
    }
}

/**
 * The complete Lists overview row overflow menu.
 *
 * Extracted so both surfaces share one definition and the user-visible action set can be exercised
 * directly; [NextcloudOverflowItems] is reused by the list detail overflow too.
 */
@Composable
internal fun ListRowOverflowMenu(
    expanded: Boolean,
    isArchivedView: Boolean,
    nextcloud: NextcloudRowActions,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!isArchivedView) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { onDismiss(); onRename() },
            )
            DropdownMenuItem(
                text = { Text("Archive") },
                onClick = { onDismiss(); onArchive() },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Restore") },
                onClick = { onDismiss(); onRestore() },
            )
        }
        DropdownMenuItem(
            text = { Text("Share as text") },
            onClick = { onDismiss(); onShare() },
            modifier = Modifier.testTag("lists_row_share"),
        )
        DropdownMenuItem(
            text = { Text("Copy to clipboard") },
            onClick = { onDismiss(); onCopy() },
            modifier = Modifier.testTag("lists_row_copy"),
        )
        DropdownMenuItem(
            text = { Text("Export Jandal file") },
            onClick = { onDismiss(); onExport() },
            modifier = Modifier.testTag("lists_row_export"),
        )
        NextcloudOverflowItems(actions = nextcloud, onDismiss = onDismiss)
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/**
 * Subtle Nextcloud state decoration for a normal Lists row (#1551).
 *
 * Healthy, sync-off and problem states stay secondary metadata; a never-connected list renders
 * nothing, so ordinary local-only rows are unchanged. Every indicator carries an accessible
 * description so the state is not colour- or icon-only.
 */
@Composable
internal fun NextcloudListIndicator(state: NextcloudListState?, modifier: Modifier = Modifier) {
    if (state == null) return
    Row(
        modifier = modifier.padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = state.icon(),
            contentDescription = state.contentDescription(),
            tint = state.tint(),
            modifier = Modifier
                .size(16.dp)
                .testTag("lists_nextcloud_indicator"),
        )
    }
}
