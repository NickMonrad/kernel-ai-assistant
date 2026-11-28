package com.kernel.ai.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.CharcoalDark
import com.kernel.ai.core.ui.theme.SandLight
import com.kernel.ai.feature.settings.HomeWidgetPinning.PinResult

/** Test tag for a Settings → Home screen row, shared by the section and its Compose tests. */
internal fun homeRowTestTag(item: HomeItem): String = "home_row_${item.name}"

/**
 * Settings → Home screen (#2194). Each row asks [onRequestPin] to pin its item. An unsupported
 * launcher opens a how-to dialog; any other outcome is reported through [onMessage].
 */
@Composable
internal fun HomeScreenSection(
    onRequestPin: (HomeItem) -> PinResult,
    onMessage: (String) -> Unit,
) {
    var howToItem by remember { mutableStateOf<HomeItem?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Home screen",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HomeItem.entries.forEach { item ->
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (val result = onRequestPin(item)) {
                            PinResult.Unsupported -> howToItem = item
                            else -> onMessage(HomeWidgetPinning.resultMessage(item, result))
                        }
                    }
                    .testTag(homeRowTestTag(item)),
                headlineContent = { Text("Add ${item.title}") },
                supportingContent = { Text(item.description) },
                leadingContent = { HomeItemPreview(item) },
            )
            HorizontalDivider()
        }
    }

    howToItem?.let { item ->
        AlertDialog(
            onDismissRequest = { howToItem = null },
            title = { Text("Add ${item.title}") },
            text = { Text(item.fallbackHowTo) },
            confirmButton = {
                TextButton(onClick = { howToItem = null }) { Text("OK") }
            },
        )
    }
}

/** Miniature stand-in for each home-screen item, so the row previews what gets pinned. */
@Composable
private fun HomeItemPreview(item: HomeItem) {
    val tileShape = RoundedCornerShape(12.dp)
    when (item) {
        HomeItem.JANDAL_WIDGET -> Box(
            modifier = Modifier
                .size(width = 96.dp, height = 40.dp)
                .clip(tileShape)
                .background(SandLight),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = CharcoalDark, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = "Ask Jandal", style = MaterialTheme.typography.labelSmall, color = CharcoalDark)
            }
        }
        HomeItem.LISTS_WIDGET -> Column(
            modifier = Modifier
                .size(width = 96.dp, height = 40.dp)
                .clip(tileShape)
                .background(SandLight)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Groceries",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = CharcoalDark,
            )
            Text(text = "• Milk", style = MaterialTheme.typography.labelSmall, color = CharcoalDark)
            Text(text = "• Eggs", style = MaterialTheme.typography.labelSmall, color = CharcoalDark)
        }
        HomeItem.LISTS_SHORTCUT -> Box(
            modifier = Modifier.size(width = 96.dp, height = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(ListsShortcut.ICON_RES),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
