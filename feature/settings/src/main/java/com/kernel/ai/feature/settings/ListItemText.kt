package com.kernel.ai.feature.settings

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

private const val ListItemUrlAnnotation = "LIST_ITEM_URL"
private const val MaxDisplayedListItemUrlLength = 48

internal data class ListItemUrlSpan(
    val start: Int,
    val endExclusive: Int,
    val url: String,
)

private val httpUrlPattern = Regex(
    pattern = "https?://[^\\s<>\"']+",
    option = RegexOption.IGNORE_CASE,
)

internal fun findListItemUrls(text: String): List<ListItemUrlSpan> = buildList {
    httpUrlPattern.findAll(text).forEach { match ->
        val candidate = match.value
        val urlLength = trimmedUrlLength(candidate)
        if (urlLength > 0) {
            add(
                ListItemUrlSpan(
                    start = match.range.first,
                    endExclusive = match.range.first + urlLength,
                    url = candidate.substring(0, urlLength),
                ),
            )
        }
    }
}

/** Keeps long URLs compact while retaining both the URL's recognizable start and end. */
internal fun truncateListItemUrl(
    url: String,
    maxLength: Int = MaxDisplayedListItemUrlLength,
): String {
    require(maxLength > 1) { "maxLength must leave room for an ellipsis" }
    if (url.length <= maxLength) return url

    val visibleLength = maxLength - 1
    val prefixLength = (visibleLength + 1) / 2
    val suffixLength = visibleLength - prefixLength
    return url.take(prefixLength) + "…" + url.takeLast(suffixLength)
}

internal fun buildListItemAnnotatedText(
    text: String,
    linkColor: Color,
    lineDecoration: TextDecoration? = null,
): AnnotatedString {
    val urls = findListItemUrls(text)
    if (urls.isEmpty()) return AnnotatedString(text)

    val linkDecoration = lineDecoration?.let {
        TextDecoration.combine(listOf(it, TextDecoration.Underline))
    } ?: TextDecoration.Underline

    return buildAnnotatedString {
        var cursor = 0
        urls.forEach { urlSpan ->
            if (urlSpan.start > cursor) append(text.substring(cursor, urlSpan.start))
            pushStringAnnotation(ListItemUrlAnnotation, urlSpan.url)
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = linkDecoration,
                ),
            ) {
                append(truncateListItemUrl(urlSpan.url))
            }
            pop()
            cursor = urlSpan.endExclusive
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
internal fun ListItemText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    activateLinks: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val annotatedText = remember(text, linkColor, style.textDecoration) {
        buildListItemAnnotatedText(text, linkColor, style.textDecoration)
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    BasicText(
        text = annotatedText,
        modifier = modifier.pointerInput(
            annotatedText,
            uriHandler,
            activateLinks,
            onClick,
            onLongClick,
        ) {
            detectTapGestures(
                onTap = { position ->
                    val offset = layoutResult?.getOffsetForPosition(position)
                    val url = offset?.let {
                        annotatedText
                            .getStringAnnotations(ListItemUrlAnnotation, it, it)
                            .firstOrNull()
                            ?.item
                    }
                    if (activateLinks && url != null) openListItemUrl(uriHandler, url) else onClick()
                },
                onLongPress = { onLongClick() },
            )
        },
        style = style.copy(color = color),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutResult = it },
    )
}

private fun trimmedUrlLength(candidate: String): Int {
    var end = candidate.length
    while (end > 0 && candidate[end - 1] in ",.!?;:") end--

    while (end > 0) {
        val closing = candidate[end - 1]
        val opening = when (closing) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> break
        }
        val prefix = candidate.substring(0, end)
        if (prefix.count { it == closing } <= prefix.count { it == opening }) break
        end--
    }
    return end
}

private fun openListItemUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return

    try {
        uriHandler.openUri(url)
    } catch (error: Exception) {
        Log.w("ListItemText", "Unable to open list item URL", error)
    }
}
