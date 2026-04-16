package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Renders the label-step of the alarm create/edit dialog in isolation for testing.
 * The production dialog is a multi-step flow (date → time → label) which cannot
 * easily be driven through Espresso without a device calendar. This wrapper skips
 * directly to the label step so we can verify tag presence, input, and callbacks.
 */
@Composable
fun AlarmDialogTestWrapper(
    onConfirm: (label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("alarm_dialog"),
        icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
        title = { Text("New alarm") },
        text = {
            Column {
                Text(
                    text = "Tomorrow at 8:00AM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth().testTag("alarm_label_input"),
                    placeholder = { Text("Label (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.takeIf { it.isNotBlank() }) },
                modifier = Modifier.testTag("alarm_save_button"),
            ) {
                Text("Set alarm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
