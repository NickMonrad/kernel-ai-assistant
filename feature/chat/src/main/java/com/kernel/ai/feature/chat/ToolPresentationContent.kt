package com.kernel.ai.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.skills.ToolPresentation

@Composable
fun ToolPresentationContent(
    presentation: ToolPresentation,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    when (presentation) {
        is ToolPresentation.Weather -> WeatherPresentationCard(presentation, modifier, compact)
        is ToolPresentation.Status -> StatusPresentationCard(presentation, modifier)
        is ToolPresentation.ListPreview -> ListPreviewPresentationCard(presentation, modifier, compact)
        is ToolPresentation.ComputedResult -> ComputedResultPresentationCard(presentation, modifier)
    }
}

@Composable
private fun WeatherPresentationCard(
    presentation: ToolPresentation.Weather,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 16.dp)) {
            Text(
                text = presentation.locationName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = presentation.emoji,
                    style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                )
                Column {
                    Text(
                        text = presentation.temperatureText,
                        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    presentation.feelsLikeText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = presentation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            presentation.highLowText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = withLeadingIcon(it, "🌡"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            weatherDetailText(presentation.humidityText, "💧")?.let { detail ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            weatherDetailText(presentation.windText, "💨")?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            weatherDetailText(presentation.precipText, "☔")?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            weatherDetailText(presentation.uvText, "☀️")?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            weatherDetailText(presentation.airQualityText, "🌬")?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            presentation.sunText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = withLeadingIcon(it, "🌅"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (presentation.forecast.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Forecast",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    presentation.forecast.forEach { day ->
                        ForecastDayCard(day, modifier = Modifier.width(100.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ForecastDayCard(
    day: ToolPresentation.ForecastDay,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = day.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
            Text(
                text = day.emoji,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = day.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
            val tempParts = listOfNotNull(day.highText, day.lowText)
            if (tempParts.isNotEmpty()) {
                Text(
                    text = tempParts.joinToString(" / "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
            day.precipText?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun weatherDetailText(value: String?, icon: String): String? =
    value?.takeIf { it.isNotBlank() }?.let { withLeadingIcon(it, icon) }

private fun withLeadingIcon(text: String, icon: String): String =
    if (text.firstOrNull()?.isHighSurrogate() == true || text.firstOrNull()?.isLowSurrogate() == true) {
        text
    } else if (text.startsWith(icon) || text.startsWith("🌡") || text.startsWith("💧") ||
        text.startsWith("💨") || text.startsWith("☔") || text.startsWith("☀") ||
        text.startsWith("🌬") || text.startsWith("🌅") || text.startsWith("🌇")
    ) {
        text
    } else {
        "$icon $text"
    }

@Composable
private fun StatusPresentationCard(
    presentation: ToolPresentation.Status,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = presentation.icon, style = MaterialTheme.typography.titleMedium)
            Column {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                presentation.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListPreviewPresentationCard(
    presentation: ToolPresentation.ListPreview,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by rememberSaveable(presentation.title, presentation.totalCount, compact) { mutableStateOf(false) }
    val collapsedCount = if (compact) 3 else 5
    val hasHiddenItems = presentation.totalCount > collapsedCount
    val visibleItems = if (hasHiddenItems && !expanded) {
        presentation.items.take(collapsedCount)
    } else {
        presentation.items
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 16.dp)) {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            if (presentation.items.isEmpty()) {
                Text(
                    text = presentation.emptyMessage ?: "No items yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                visibleItems.forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (hasHiddenItems) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text(
                            text = if (expanded) {
                                "Show less"
                            } else {
                                "+ ${presentation.totalCount - collapsedCount} more"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputedResultPresentationCard(
    presentation: ToolPresentation.ComputedResult,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = presentation.primaryText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = presentation.contextText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            presentation.breakdownText?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
