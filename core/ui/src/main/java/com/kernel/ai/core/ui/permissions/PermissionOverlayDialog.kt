package com.kernel.ai.core.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/**
 * A single action button within a [PermissionOverlayDialog].
 *
 * @param label Display text for the button.
 * @param testTag UIAutomator / Compose test tag for the button node.
 * @param onClick Callback invoked when the button is tapped.
 * @param isPrimary When true, renders as a filled Button (unless the label is "Not now").
 * @param isDestructive When true, applies error colouring (not currently used in permission flows).
 */
data class PermissionDialogAction(
    val label: String,
    val testTag: String,
    val onClick: () -> Unit,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false,
)

/**
 * Shared permission-overlay dialog with a custom layout that supports
 * a primary/secondary/tertiary action hierarchy.
 *
 * Uses a Material3 [BasicAlertDialog] with a [Surface] body so CTA buttons
 * live in a vertical action list below the body text rather than being
 * constrained to [AlertDialog]'s confirmButton/dismissButton slots. This
 * avoids putting fallback CTAs inside body text and supports 3+ actions
 * without layout hacks.
 *
 * Action order conventions (followed by callers):
 * 1. Primary: request or repair action.
 * 2. Secondary: fallback / alternative action.
 * 3. Tertiary: cancel ("Not now").
 *
 * "Not now" is always rendered as a [TextButton] regardless of [PermissionDialogAction.isPrimary].
 *
 * @param title Dialog title.
 * @param body Concise explanatory paragraph.
 * @param actions Action buttons in display order. At least one is required.
 * @param dialogTestTag UIAutomator / Compose test tag on the dialog surface.
 * @param onDismissRequest Called when the user taps outside or presses back.
 * @param modifier Optional [Modifier] applied to the dialog [Surface].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionOverlayDialog(
    title: String,
    body: String,
    actions: List<PermissionDialogAction>,
    dialogTestTag: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(actions.isNotEmpty()) { "PermissionOverlayDialog requires at least one action" }
    val bodyScrollState = rememberScrollState()

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier
                .semantics { testTagsAsResourceId = true }
                .semantics { testTag = dialogTestTag },
            shape = AlertDialogDefaults.shape,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = body,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(bodyScrollState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEachIndexed { index, action ->
                        PermissionDialogActionButton(
                            action = action,
                            isLast = index == actions.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionDialogActionButton(
    action: PermissionDialogAction,
    isLast: Boolean,
) {
    val isNotNow = action.label.equals("Not now", ignoreCase = true) ||
        action.testTag.endsWith("_not_now")

    val buttonModifier = Modifier
        .fillMaxWidth()
        .semantics { testTag = action.testTag }
        .semantics { testTagsAsResourceId = true }

    when {
        action.isPrimary && !isNotNow -> Button(
            onClick = action.onClick,
            modifier = buttonModifier,
        ) {
            Text(action.label)
        }

        isNotNow -> TextButton(
            onClick = action.onClick,
            modifier = buttonModifier,
        ) {
            Text(action.label)
        }

        else -> OutlinedButton(
            onClick = action.onClick,
            modifier = buttonModifier,
        ) {
            Text(action.label)
        }
    }
}
