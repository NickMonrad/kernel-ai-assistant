package com.kernel.ai.core.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.JandalTheme

/** Accent uppercase label that starts a group of cards. Announced as a heading. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val accent = JandalTheme.colors.accent
    Row(
        modifier = modifier.clearAndSetSemantics {
            heading()
            contentDescription = text
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent) }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
    }
}

/** Bottom sheet explaining a card. Dismissed with its Done button or by swiping/scrim tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplanationSheet(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent),
            ) {
                Text("Done")
            }
        }
    }
}

/**
 * A switch with a title and a one-line description, used as sub-content of a choice card. The whole row toggles.
 * [onMore] adds a "More" text button; [moreDescription] is its accessible name.
 */
@Composable
fun SwitchSubSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    moreDescription: String = "More",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        onMore?.let {
            TextButton(
                onClick = it,
                modifier = Modifier
                    .offset(x = (-12).dp)
                    .semantics { contentDescription = moreDescription },
                colors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent),
            ) {
                Text("More")
            }
        }
    }
}
