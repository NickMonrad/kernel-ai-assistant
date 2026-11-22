package com.kernel.ai.core.ui.terms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One block of the bundled TERMS.md, as the in-app Terms sheet draws it (#1998). */
internal sealed interface TermsBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : TermsBlock
    data class Paragraph(val text: AnnotatedString) : TermsBlock
    data class Bullet(val text: AnnotatedString) : TermsBlock
}

/**
 * Parses the subset of Markdown that TERMS.md uses: `#` / `##` headings, `- ` bullets, paragraphs,
 * `**bold**`, `` `code` ``, `[text](url)`, and a paragraph wrapped in `_..._` (drawn italic).
 * Links to relative paths (LICENSE, NOTICE, PRIVACY.md) stay as plain text: the app cannot open them.
 */
internal fun parseTermsBlocks(markdown: String, linkColor: Color): List<TermsBlock> {
    val blocks = mutableListOf<TermsBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val raw = paragraph.toString().trim()
        paragraph.clear()
        val italic = raw.length > 2 && raw.startsWith("_") && raw.endsWith("_")
        val body = if (italic) raw.substring(1, raw.length - 1) else raw
        blocks += TermsBlock.Paragraph(parseTermsInline(body, linkColor, italic = italic))
    }

    for (line in markdown.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                val title = trimmed.drop(level).trim()
                blocks += TermsBlock.Heading(level, parseTermsInline(title, linkColor))
            }
            trimmed.startsWith("- ") -> {
                flushParagraph()
                blocks += TermsBlock.Bullet(parseTermsInline(trimmed.drop(2).trim(), linkColor))
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flushParagraph()
    return blocks
}

internal fun parseTermsInline(text: String, linkColor: Color, italic: Boolean = false): AnnotatedString {
    val parsed = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text[i] == '[' && linkEnd(text, i) > 0 -> {
                    val close = text.indexOf(']', i)
                    val paren = linkEnd(text, i)
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, paren)
                    if (url.startsWith("https://") || url.startsWith("mailto:")) {
                        val link = LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        )
                        withLink(link) { append(label) }
                    } else {
                        append(label)
                    }
                    i = paren + 1
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
    if (!italic) return parsed
    return buildAnnotatedString { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(parsed) } }
}

/** Index of the `)` closing a `[label](url)` starting at [start], or -1 when the text is not such a link. */
private fun linkEnd(text: String, start: Int): Int {
    val close = text.indexOf(']', start)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return -1
    return text.indexOf(')', close + 2)
}

/** Draws parsed TERMS.md text in a column. Used by the Terms sheet. */
@Composable
internal fun TermsDocumentBody(termsText: String, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(termsText, linkColor) { parseTermsBlocks(termsText, linkColor) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is TermsBlock.Heading -> Text(
                    text = block.text,
                    style = if (block.level <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                is TermsBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is TermsBlock.Bullet -> Text(
                    text = AnnotatedString("\u2022 ") + block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}
