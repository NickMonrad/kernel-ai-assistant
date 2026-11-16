package com.kernel.ai.feature.chat.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.JandalTheme

/**
 * Pinned footer (spec Shared parts 5): a 1 dp top border on the page background, the primary button, then Back and
 * Skip. Short windows use [SetupFooterButtons] inside the scrolling page instead.
 */
@Composable
internal fun SetupFooter(
    primaryLabel: String,
    skipLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .testTag(SETUP_FOOTER_TAG),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupFooterButtons(primaryLabel, skipLabel, onPrimary, onBack, onSkip)
        }
    }
}

/** Primary button, then Back (left) and Skip (right) text buttons in the accent colour. */
@Composable
internal fun SetupFooterButtons(
    primaryLabel: String,
    skipLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val accent = JandalTheme.colors.accent
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(primaryLabel)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
        ) { Text("Back") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
        ) { Text(skipLabel) }
    }
}

/**
 * Step content that scrolls above the pinned footer. While more content lies below, a 48 dp fade to the page
 * background and a "More below" hint are drawn over the bottom edge. The overlay takes no touches and has no
 * semantics, so content stays reachable by swiping and TalkBack does not announce it.
 */
@Composable
internal fun FadingScrollArea(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp)
                .testTag(SETUP_WIZARD_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        AnimatedVisibility(
            visible = scrollState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // testTag goes before clearAndSetSemantics, which would otherwise drop it.
            Column(modifier = Modifier.fillMaxWidth().testTag(SETUP_MORE_BELOW_TAG).clearAndSetSemantics {}) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(background).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "More below",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
