package com.kernel.ai.feature.chat.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.ChipStatus
import com.kernel.ai.core.ui.ExplanationSheet
import com.kernel.ai.core.ui.StatusChip
import com.kernel.ai.core.ui.theme.JandalTheme

/** A button on a [SettingCard]. */
internal data class CardAction(val label: String, val onClick: () -> Unit)

private val CARD_PADDING = 16.dp
private val ICON_TILE = 40.dp
private val TILE_GAP = 14.dp

/**
 * One Make Jandal yours card (spec Shared parts 4): an icon tile, the title with its status chip, a description and
 * an optional caveat, read by TalkBack as one header. Below it, the actions start at the title edge: a tonal
 * [primary], a text [secondary], and **Why?** when [why] is set. Needs access adds a warning border and colours the
 * caveat. The chip carries the test tag `make_yours_status_<id>`.
 */
@Composable
internal fun SettingCard(
    id: String,
    icon: ImageVector,
    title: String,
    status: ChipStatus,
    description: String,
    modifier: Modifier = Modifier,
    caveat: String? = null,
    primary: CardAction? = null,
    secondary: CardAction? = null,
    why: String? = null,
) {
    val colors = JandalTheme.colors
    val needsAccess = status == ChipStatus.NeedsAccess
    var showWhy by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (needsAccess) BorderStroke(1.dp, colors.warningOutline) else null,
    ) {
        Column(modifier = Modifier.padding(vertical = CARD_PADDING)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CARD_PADDING)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(ICON_TILE)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Title and chip share a line when they fit (chip at the end, as in the mockup); at large font
                    // the chip moves under the title instead of squeezing it (README Shared parts 2 tag rule).
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(end = 8.dp).semantics { heading() },
                        )
                        StatusChip(status = status, modifier = Modifier.testTag("make_yours_status_$id"))
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    caveat?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (needsAccess) colors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (primary != null || secondary != null || why != null) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CARD_PADDING + ICON_TILE + TILE_GAP, end = CARD_PADDING, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    primary?.let { action ->
                        FilledTonalButton(
                            onClick = action.onClick,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) { Text(action.label) }
                    }
                    secondary?.let { action -> AccentTextButton(action.label, action.onClick) }
                    if (why != null) {
                        AccentTextButton(
                            label = "Why?",
                            onClick = { showWhy = true },
                            modifier = Modifier.semantics { contentDescription = "Why? $title" },
                        )
                    }
                }
            }
        }
    }
    if (showWhy && why != null) {
        ExplanationSheet(title = title, body = why, onDismiss = { showWhy = false })
    }
}

@Composable
private fun AccentTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent),
    ) { Text(label) }
}
