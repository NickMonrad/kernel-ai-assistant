package com.kernel.ai.feature.chat.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.JandalTheme

/**
 * "Step N of 4" above a segmented bar (spec Shared parts 1). Done and current segments use the accent. One node for
 * TalkBack, read as "Step N of 4", so the visible text is not read twice.
 */
@Composable
internal fun SetupStepHeader(step: Int, total: Int = 4, modifier: Modifier = Modifier) {
    val accent = JandalTheme.colors.accent
    val rest = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Step $step of $total"
                progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..total.toFloat(), steps = total - 1)
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Step $step of $total",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < step) accent else rest),
                )
            }
        }
    }
}
