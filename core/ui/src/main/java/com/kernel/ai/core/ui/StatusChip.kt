package com.kernel.ai.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.JandalTheme

enum class ChipStatus { On, OnAtFinish, Off, NotAvailable, NeedsAccess }

enum class InfoTagStyle { Success, Neutral }

/**
 * Status pill for a setup or settings card: colour, icon and text together. It has no semantics node of its own,
 * so its text merges into the parent card header for screen readers.
 */
@Composable
fun StatusChip(
    status: ChipStatus,
    modifier: Modifier = Modifier,
    label: String = defaultLabel(status),
) {
    val colors = JandalTheme.colors
    val (container, content, border, icon) = when (status) {
        ChipStatus.On -> Quad(colors.successContainer, colors.onSuccessContainer, null, Icons.Filled.Check)
        ChipStatus.OnAtFinish -> Quad(Color.Transparent, colors.accent, BorderStroke(1.dp, colors.accent), Icons.Outlined.Schedule)
        ChipStatus.Off -> Quad(colors.neutralChip, colors.onNeutralChip, null, Icons.Outlined.RadioButtonUnchecked)
        ChipStatus.NotAvailable -> Quad(colors.neutralChip, colors.onNeutralChip, null, Icons.Outlined.Remove)
        ChipStatus.NeedsAccess -> Quad(colors.warningContainer, colors.onWarningContainer, null, Icons.Outlined.WarningAmber)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = content)
            Text(
                text = label,
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Small descriptive tag such as "Recommended" or a download size. Carries no status meaning on its own. */
@Composable
fun InfoTag(
    text: String,
    style: InfoTagStyle,
    modifier: Modifier = Modifier,
) {
    val colors = JandalTheme.colors
    val (container, content) = when (style) {
        InfoTagStyle.Success -> colors.successContainer to colors.onSuccessContainer
        InfoTagStyle.Neutral -> colors.neutralChip to colors.onNeutralChip
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private data class Quad(
    val container: Color,
    val content: Color,
    val border: BorderStroke?,
    val icon: ImageVector,
)

private fun defaultLabel(status: ChipStatus): String = when (status) {
    ChipStatus.On -> "On"
    ChipStatus.OnAtFinish -> "On at Finish"
    ChipStatus.Off -> "Off"
    ChipStatus.NotAvailable -> "Not available"
    ChipStatus.NeedsAccess -> "Needs access"
}
