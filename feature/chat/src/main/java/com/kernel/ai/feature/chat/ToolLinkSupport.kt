package com.kernel.ai.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val RAW_URL_REGEX = Regex("""https?://[^\s<>"()]+[^\s<>"().,!?;:]""")

internal fun extractUrls(text: String?): List<String> =
    text
        ?.let { source -> RAW_URL_REGEX.findAll(source).map { it.value }.toList() }
        ?.distinct()
        ?: emptyList()

internal fun collectAdditionalUrls(visibleText: String, vararg candidateTexts: String?): List<String> {
    val visibleUrls = extractUrls(visibleText).toSet()
    return candidateTexts
        .flatMap { extractUrls(it) }
        .distinct()
        .filterNot { it in visibleUrls }
}

@Composable
internal fun ToolLinkList(
    urls: List<String>,
    modifier: Modifier = Modifier,
) {
    if (urls.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        urls.forEach { url ->
            MarkdownContent(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
