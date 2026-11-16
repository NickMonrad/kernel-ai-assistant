package com.kernel.ai.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kernel.ai.core.ui.theme.JandalTheme

/** Left edge of the card title: 16 dp card padding, the 24 dp radio, then a 10 dp gap. Sub-content aligns here. */
val CHOICE_TITLE_START = 50.dp

/** Radio group with a collection role so screen readers announce the item count. */
@Composable
fun ChoiceGroup(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { collectionInfo = CollectionInfo(rowCount = count, columnCount = 1) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * One radio option. Only the header (radio, title, tag, description) is selectable. [selected] draws a 2 dp accent
 * border over a tinted fill. [subContent] renders below a divider, indented to [CHOICE_TITLE_START], for switches
 * and sample buttons that belong to this option; each of its controls is its own touch target.
 */
@Composable
fun ChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    index: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    tag: (@Composable () -> Unit)? = null,
    extraLine: String? = null,
    subContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val accent = JandalTheme.colors.accent
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_FILL_ALPHA).compositeOver(container)
    } else {
        container
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = fill,
        border = if (selected) BorderStroke(2.dp, accent) else null,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                    .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(RADIO_GAP))
                leadingIcon?.let { icon ->
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(RADIO_GAP))
                }
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        tag?.invoke()
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    extraLine?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (subContent != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Column(
                    modifier = Modifier.padding(start = CHOICE_TITLE_START, end = 16.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = subContent,
                )
            }
        }
    }
}

private val RADIO_GAP = 10.dp
private const val SELECTED_FILL_ALPHA = 0.35f
