package com.kernel.ai.feature.chat

import android.util.Log
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.KernelAITheme
import kotlinx.coroutines.delay

private const val TAG = "KernelAI"

// ── Sealed block types ─────────────────────────────────────────────────────────

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class OrderedList(val items: List<String>) : MarkdownBlock()
    data class FencedCode(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

// ── System tag stripping ───────────────────────────────────────────────────────

/**
 * Strips model routing artefacts such as `<think>…</think>`, `<tool_call>…</tool_call>`,
 * `<function_call>…</function_call>`, and any other `<snake_case_tag>…</snake_case_tag>` pair
 * as well as self-closing variants like `<tag_name/>`.
 */
private val SYSTEM_TAG_PAIRED_REGEX = Regex(
    "<[a-z][a-z_]*(?:\\s[^>]*)?>.*?</[a-z][a-z_]*>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

private val SYSTEM_TAG_SELF_CLOSING_REGEX = Regex("<[a-z][a-z_]*/?>")

private fun stripSystemTags(text: String): String =
    text.replace(SYSTEM_TAG_PAIRED_REGEX, "")
        .replace(SYSTEM_TAG_SELF_CLOSING_REGEX, "")
        .trim()

// ── LaTeX-to-Unicode conversion ────────────────────────────────────────────────

/**
 * Common LaTeX commands that LLMs emit in text (especially arrows, math operators,
 * Greek letters, and comparison symbols). Mapped to their Unicode equivalents so
 * they render properly without a full LaTeX engine.
 *
 * Handles both inline math (`$\rightarrow$`) and bare commands (`\rightarrow`).
 */
private val LATEX_TO_UNICODE = mapOf(
    // Arrows
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\leftrightarrow" to "↔",
    "\\Rightarrow" to "⇒",
    "\\Leftarrow" to "⇐",
    "\\Leftrightarrow" to "⇔",
    "\\uparrow" to "↑",
    "\\downarrow" to "↓",
    "\\to" to "→",
    "\\gets" to "←",
    "\\mapsto" to "↦",
    "\\longrightarrow" to "⟶",
    "\\longleftarrow" to "⟵",

    // Math operators
    "\\times" to "×",
    "\\div" to "÷",
    "\\pm" to "±",
    "\\mp" to "∓",
    "\\cdot" to "·",
    "\\star" to "⋆",
    "\\circ" to "∘",
    "\\bullet" to "•",

    // Comparison / relational
    "\\neq" to "≠",
    "\\ne" to "≠",
    "\\leq" to "≤",
    "\\le" to "≤",
    "\\geq" to "≥",
    "\\ge" to "≥",
    "\\approx" to "≈",
    "\\equiv" to "≡",
    "\\sim" to "∼",
    "\\propto" to "∝",
    "\\ll" to "≪",
    "\\gg" to "≫",

    // Set / logic
    "\\in" to "∈",
    "\\notin" to "∉",
    "\\subset" to "⊂",
    "\\supset" to "⊃",
    "\\subseteq" to "⊆",
    "\\supseteq" to "⊇",
    "\\cup" to "∪",
    "\\cap" to "∩",
    "\\emptyset" to "∅",
    "\\forall" to "∀",
    "\\exists" to "∃",
    "\\neg" to "¬",
    "\\land" to "∧",
    "\\lor" to "∨",
    "\\wedge" to "∧",
    "\\vee" to "∨",

    // Greek letters (commonly used)
    "\\alpha" to "α",
    "\\beta" to "β",
    "\\gamma" to "γ",
    "\\delta" to "δ",
    "\\epsilon" to "ε",
    "\\theta" to "θ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\pi" to "π",
    "\\sigma" to "σ",
    "\\tau" to "τ",
    "\\phi" to "φ",
    "\\omega" to "ω",
    "\\Delta" to "Δ",
    "\\Sigma" to "Σ",
    "\\Omega" to "Ω",

    // Miscellaneous
    "\\infty" to "∞",
    "\\partial" to "∂",
    "\\nabla" to "∇",
    "\\sqrt" to "√",
    "\\sum" to "∑",
    "\\prod" to "∏",
    "\\int" to "∫",
    "\\degree" to "°",
    "\\ldots" to "…",
    "\\cdots" to "⋯",
    "\\checkmark" to "✓",
    "\\dagger" to "†",
)

// Pre-compiled regexes for $\cmd$ wrappers — zero runtime compilation cost.
private val DOLLAR_WRAPPED_REPLACEMENTS: List<Pair<Regex, String>> =
    LATEX_TO_UNICODE.map { (latex, unicode) ->
        Regex("\\$" + Regex.escape(latex) + "\\$") to unicode
    }

// Pre-compiled regexes for bare \cmd with negative lookahead for [A-Za-z].
// Prevents \to matching inside \top, \le inside \left, \in inside \input, etc.
// Sorted longest-first so \longrightarrow matches before \to.
private val BARE_LATEX_REPLACEMENTS: List<Pair<Regex, String>> =
    LATEX_TO_UNICODE.entries
        .sortedByDescending { it.key.length }
        .map { (latex, unicode) ->
            Regex(Regex.escape(latex) + """(?![A-Za-z])""") to unicode
        }

/**
 * Replaces LaTeX commands with Unicode equivalents, skipping code fences.
 *
 * Handles:
 * - Inline math wrappers: `$\rightarrow$` → `→`
 * - Bare commands: `\rightarrow` → `→` (with word-boundary guard to avoid
 *   corrupting `\left`, `\top`, `\input`, etc.)
 *
 * Lines inside fenced code blocks (``` ... ```) are left untouched.
 *
 * Does NOT handle complex LaTeX (fractions, superscripts, etc.) — those remain as-is
 * since they can't be meaningfully rendered as plain Unicode text.
 */
private fun convertLatexToUnicode(text: String): String {
    val lines = text.lines()
    var inCodeFence = false
    val processed = lines.map { line ->
        if (line.trimStart().startsWith("```")) {
            inCodeFence = !inCodeFence
            line
        } else if (inCodeFence) {
            line
        } else {
            convertLatexLine(line)
        }
    }
    return processed.joinToString("\n")
}

private fun convertLatexLine(line: String): String {
    var result = line
    for ((regex, unicode) in DOLLAR_WRAPPED_REPLACEMENTS) {
        result = regex.replace(result, Regex.escapeReplacement(unicode))
    }
    for ((regex, unicode) in BARE_LATEX_REPLACEMENTS) {
        result = regex.replace(result, Regex.escapeReplacement(unicode))
    }
    return result
}

// ── Block-level regex patterns ─────────────────────────────────────────────────

private val HEADER_REGEX       = Regex("^(#{1,6})\\s+(.*)")
private val BULLET_REGEX       = Regex("^[-*+]\\s+(.*)")
private val ORDERED_REGEX      = Regex("^\\d+\\.\\s+(.*)")
private val TABLE_ROW_REGEX    = Regex("^\\|(.+)\\|\\s*\$")
private val TABLE_SEP_REGEX    = Regex("^\\|[\\s|:-]+\\|\\s*\$")
private val FENCED_START_REGEX = Regex("^```(\\w*)\\s*\$")
private val FENCED_END_REGEX   = Regex("^```\\s*\$")

// ── Block parser ───────────────────────────────────────────────────────────────

/**
 * Parses [text] into a list of [MarkdownBlock]s.
 *
 * Stream-safe: incomplete blocks (unclosed fenced code, partial table, unclosed inline span)
 * are emitted as plain [MarkdownBlock.Paragraph] rather than crashing or silently dropping
 * content. This allows safe calling on every streaming token.
 */
private fun parseBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines  = text.lines()
    var i      = 0

    while (i < lines.size) {
        val line = lines[i]

        // ── Fenced code block ──────────────────────────────────────────────────
        val fencedStart = FENCED_START_REGEX.matchEntire(line)
        if (fencedStart != null) {
            val language  = fencedStart.groupValues[1]
            val codeLines = mutableListOf<String>()
            var closed    = false
            i++
            while (i < lines.size) {
                if (FENCED_END_REGEX.matches(lines[i])) {
                    closed = true
                    i++
                    break
                }
                codeLines.add(lines[i])
                i++
            }
            if (closed) {
                blocks.add(MarkdownBlock.FencedCode(language, codeLines.joinToString("\n")))
            } else {
                // Stream-safe: unclosed block → plain text so rendering never breaks
                val raw = buildString {
                    append("```$language\n")
                    append(codeLines.joinToString("\n"))
                }
                blocks.add(MarkdownBlock.Paragraph(raw))
                Log.d(TAG, "MarkdownRenderer: unclosed fenced code block treated as plain text")
            }
            continue
        }

        // ── Heading ────────────────────────────────────────────────────────────
        val headerMatch = HEADER_REGEX.matchEntire(line)
        if (headerMatch != null) {
            blocks.add(MarkdownBlock.Header(headerMatch.groupValues[1].length, headerMatch.groupValues[2]))
            i++
            continue
        }

        // ── Blockquote ─────────────────────────────────────────────────────────
        if (line.startsWith("> ") || line == ">") {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && (lines[i].startsWith("> ") || lines[i] == ">")) {
                quoteLines.add(lines[i].removePrefix("> ").removePrefix(">"))
                i++
            }
            blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
            continue
        }

        // ── Bullet list ────────────────────────────────────────────────────────
        if (BULLET_REGEX.matches(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && BULLET_REGEX.matches(lines[i])) {
                items.add(BULLET_REGEX.matchEntire(lines[i])!!.groupValues[1])
                i++
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // ── Ordered list ───────────────────────────────────────────────────────
        if (ORDERED_REGEX.matches(line)) {
            val items = mutableListOf<String>()
            while (i < lines.size && ORDERED_REGEX.matches(lines[i])) {
                items.add(ORDERED_REGEX.matchEntire(lines[i])!!.groupValues[1])
                i++
            }
            blocks.add(MarkdownBlock.OrderedList(items))
            continue
        }

        // ── Table (header row + separator on next line) ───────────────────────
        if (TABLE_ROW_REGEX.matches(line) &&
            i + 1 < lines.size &&
            TABLE_SEP_REGEX.matches(lines[i + 1])
        ) {
            val headers = parseTableRow(line)
            i += 2 // skip header row + separator row
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && TABLE_ROW_REGEX.matches(lines[i])) {
                rows.add(parseTableRow(lines[i]))
                i++
            }
            blocks.add(MarkdownBlock.Table(headers, rows))
            continue
        }

        // ── Blank line separator ───────────────────────────────────────────────
        if (line.isBlank()) {
            i++
            continue
        }

        // ── Paragraph ─────────────────────────────────────────────────────────
        // Accumulate consecutive non-special lines into one paragraph.
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val pl = lines[i]
            if (pl.isBlank()) break
            if (HEADER_REGEX.matches(pl)) break
            if (pl.startsWith("> ") || pl == ">") break
            if (BULLET_REGEX.matches(pl)) break
            if (ORDERED_REGEX.matches(pl)) break
            if (FENCED_START_REGEX.matches(pl)) break
            if (TABLE_ROW_REGEX.matches(pl) &&
                i + 1 < lines.size &&
                TABLE_SEP_REGEX.matches(lines[i + 1])
            ) break
            paraLines.add(pl)
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString("\n")))
        }
    }

    return blocks
}

private fun parseTableRow(line: String): List<String> =
    line.trim().trimStart('|').trimEnd('|').split("|").map { it.trim() }

// ── Inline span renderer ───────────────────────────────────────────────────────

private data class InlineSpan(val start: Int, val end: Int, val type: String, val content: String)

/**
 * Converts [text] to an [AnnotatedString] with inline styling:
 * bold, italic, strikethrough, inline code, and tappable URLs.
 *
 * Overlapping spans are resolved by earliest start (then longest span wins for ties).
 * Unclosed spans are never applied — they silently fall back to plain text, keeping
 * the renderer safe during streaming.
 */
private fun renderInlineSpans(
    text: String,
    surfaceVariant: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val spans = mutableListOf<InlineSpan>()

    // Bold: **text** or __text__
    Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").findAll(text).forEach { m ->
        val content = m.groupValues[1].ifEmpty { m.groupValues[2] }
        spans.add(InlineSpan(m.range.first, m.range.last + 1, "BOLD", content))
    }

    // Italic: *text* or _text_ — explicitly excludes ** and __ via lookahead/lookbehind
    Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)")
        .findAll(text)
        .forEach { m ->
            val content = m.groupValues[1].ifEmpty { m.groupValues[2] }
            spans.add(InlineSpan(m.range.first, m.range.last + 1, "ITALIC", content))
        }

    // Strikethrough: ~~text~~
    Regex("~~(.+?)~~").findAll(text).forEach { m ->
        spans.add(InlineSpan(m.range.first, m.range.last + 1, "STRIKETHROUGH", m.groupValues[1]))
    }

    // Inline code: `code`
    Regex("`([^`]+)`").findAll(text).forEach { m ->
        spans.add(InlineSpan(m.range.first, m.range.last + 1, "CODE", m.groupValues[1]))
    }

    // URLs (Java Patterns — handles http/https/www links)
    val urlMatcher = Patterns.WEB_URL.matcher(text)
    while (urlMatcher.find()) {
        spans.add(InlineSpan(urlMatcher.start(), urlMatcher.end(), "URL", urlMatcher.group()))
    }

    // Sort: earliest start first; for equal starts, prefer longer spans (negative length first)
    spans.sortWith(compareBy({ it.start }, { it.start - it.end }))

    return buildAnnotatedString {
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue // skip overlapping spans
            if (span.start > cursor) append(text.substring(cursor, span.start))
            when (span.type) {
                "BOLD"          -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.content) }
                "ITALIC"        -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.content) }
                "STRIKETHROUGH" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(span.content) }
                "CODE"          -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = surfaceVariant)) { append(span.content) }
                "URL"           -> {
                    pushStringAnnotation("URL", span.content)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(span.content) }
                    pop()
                }
            }
            cursor = span.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

// ── Block composables ──────────────────────────────────────────────────────────

@Suppress("DEPRECATION") // ClickableText: BasicText.onClick not available in this Compose version
@Composable
private fun BlockContent(block: MarkdownBlock, baseStyle: TextStyle) {
    val uriHandler      = LocalUriHandler.current
    val surfaceVariant  = MaterialTheme.colorScheme.surfaceVariant
    val linkColor       = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor    = MaterialTheme.colorScheme.primary

    when (block) {
        // ── Paragraph ──────────────────────────────────────────────────────────
        is MarkdownBlock.Paragraph -> {
            val annotated = remember(block.text, surfaceVariant, linkColor) {
                renderInlineSpans(block.text, surfaceVariant, linkColor)
            }
            ClickableText(
                text     = annotated,
                style    = baseStyle,
                onClick  = { offset ->
                    annotated.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                },
            )
        }

        // ── Heading ────────────────────────────────────────────────────────────
        is MarkdownBlock.Header -> {
            val headingStyle = when (block.level) {
                1    -> MaterialTheme.typography.headlineLarge
                2    -> MaterialTheme.typography.headlineMedium
                3    -> MaterialTheme.typography.headlineSmall
                4    -> MaterialTheme.typography.titleLarge
                5    -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.labelLarge
            }
            val annotated = remember(block.text, surfaceVariant, linkColor) {
                renderInlineSpans(block.text, surfaceVariant, linkColor)
            }
            ClickableText(
                text    = annotated,
                style   = headingStyle,
                onClick = { offset ->
                    annotated.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()?.let { uriHandler.openUri(it.item) }
                },
            )
        }

        // ── Blockquote ─────────────────────────────────────────────────────────
        is MarkdownBlock.Blockquote -> {
            val annotated = remember(block.text, surfaceVariant, linkColor) {
                renderInlineSpans(block.text, surfaceVariant, linkColor)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(4.dp))
                    .background(surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(primaryColor),
                )
                ClickableText(
                    text     = annotated,
                    style    = baseStyle.copy(fontStyle = FontStyle.Italic, color = onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    onClick  = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { uriHandler.openUri(it.item) }
                    },
                )
            }
        }

        // ── Bullet list ────────────────────────────────────────────────────────
        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEach { item ->
                    val annotated = remember(item, surfaceVariant, linkColor) {
                        renderInlineSpans("• $item", surfaceVariant, linkColor)
                    }
                    ClickableText(
                        text    = annotated,
                        style   = baseStyle,
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                        },
                    )
                }
            }
        }

        // ── Ordered list ───────────────────────────────────────────────────────
        is MarkdownBlock.OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEachIndexed { idx, item ->
                    val annotated = remember(item, surfaceVariant, linkColor) {
                        renderInlineSpans("${idx + 1}. $item", surfaceVariant, linkColor)
                    }
                    ClickableText(
                        text    = annotated,
                        style   = baseStyle,
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                        },
                    )
                }
            }
        }

        // ── Fenced code block ──────────────────────────────────────────────────
        is MarkdownBlock.FencedCode -> {
            FencedCodeBlock(language = block.language, code = block.code)
        }

        // ── Table ──────────────────────────────────────────────────────────────
        is MarkdownBlock.Table -> {
            MarkdownTable(headers = block.headers, rows = block.rows, baseStyle = baseStyle)
        }
    }
}

/**
 * Renders a fenced code block with:
 *  - `surfaceVariant` background and `RoundedCornerShape(8.dp)`
 *  - `FontFamily.Monospace`, `softWrap = false`, horizontal scroll
 *  - A copy-to-clipboard button anchored top-right; icon changes to ✓ for 2 seconds after copy
 *  - An optional language label above the block (e.g. "yaml", "bash") when present
 *  - Vertical padding (8.dp) to visually separate the block from surrounding text
 */
@Composable
private fun FencedCodeBlock(language: String = "", code: String, modifier: Modifier = Modifier) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (language.isNotBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    // Right padding leaves room so long lines don't slide under the copy button
                    .padding(start = 12.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text     = code.trimEnd('\n'),
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                )
            }

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                    Log.d(TAG, "MarkdownRenderer: code block copied to clipboard")
                },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector     = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    modifier        = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Renders a Markdown table as a scrollable grid with per-cell borders.
 * The header row is displayed on a `surfaceVariant` background with bold text.
 * If a data row has fewer columns than the header, missing cells are rendered as empty.
 */
@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val outlineColor   = MaterialTheme.colorScheme.outline
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, outlineColor, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
        ) {
            // Header row
            Row(modifier = Modifier.background(surfaceVariant)) {
                headers.forEach { header ->
                    Text(
                        text     = header,
                        style    = baseStyle.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .border(0.5.dp, outlineColor)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // Data rows
            rows.forEach { row ->
                Row {
                    headers.indices.forEach { idx ->
                        Text(
                            text     = row.getOrElse(idx) { "" },
                            style    = baseStyle,
                            modifier = Modifier
                                .border(0.5.dp, outlineColor)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Public API ─────────────────────────────────────────────────────────────────

/**
 * Renders [text] as full Markdown inside a [Column].
 *
 * Supports:
 *  - **Bold**, *italic*, ~~strikethrough~~, `inline code`, tappable URLs (inline)
 *  - H1–H6 headings, blockquotes, bullet lists, ordered lists (block)
 *  - Fenced code blocks with copy button and horizontal scroll (block)
 *  - Tables with scrollable header + data rows (block)
 *  - LaTeX-to-Unicode conversion for common symbols (arrows, math, Greek letters)
 *
 * Model routing artefacts (`<think>`, `<tool_call>`, etc.) are stripped before parsing.
 * LaTeX commands (`$\rightarrow$`, `\times`, etc.) are converted to Unicode equivalents.
 * The renderer is stream-safe: incomplete syntax gracefully falls back to plain text.
 *
 * @param text     Raw Markdown text (may include model artefact tags).
 * @param modifier [Modifier] applied to the outer [Column].
 * @param style    Base [TextStyle] for body text; headings override this with Material 3 type scale.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val cleaned = remember(text) { convertLatexToUnicode(stripSystemTags(text)) }
    val blocks  = remember(cleaned) { parseBlocks(cleaned) }

    Column(
        modifier           = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            BlockContent(block = block, baseStyle = style)
        }
    }
}

// ── Previews ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Markdown — full sample")
@Composable
private fun MarkdownContentPreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            text = """
                # Heading 1
                ## Heading 2
                ### Heading 3

                This is a **bold** and *italic* paragraph with a `code span` and ~~strikethrough~~.
                Visit https://example.com for more.

                > This is a blockquote with *italic* text and a [link](https://example.com).

                - Item one
                - Item **two**
                - Item three

                1. First item
                2. Second item
                3. Third item

                ```kotlin
                fun greet(name: String) {
                    println("Hello, ${'$'}name!")
                }
                ```

                | Name  | Age | City    |
                |-------|-----|---------|
                | Alice | 30  | London  |
                | Bob   | 25  | Berlin  |
            """.trimIndent(),
        )
    }
}

@Preview(showBackground = true, name = "Markdown — stream-safe incomplete block")
@Composable
private fun MarkdownStreamSafePreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            // Unclosed fenced block — must render as plain text, not crash
            text = "Here is some text\n```kotlin\nfun incomplete() {",
        )
    }
}

@Preview(showBackground = true, name = "Markdown — tag stripping")
@Composable
private fun MarkdownTagStrippingPreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            text = "<think>internal reasoning</think>The visible answer is **here**.",
        )
    }
}

@Preview(showBackground = true, name = "Markdown — LaTeX to Unicode")
@Composable
private fun MarkdownLatexPreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            text = """
                ## LaTeX Symbol Conversion

                Arrows: A ${'$'}\rightarrow${'$'} B ${'$'}\leftarrow${'$'} C ${'$'}\Rightarrow${'$'} D

                Math: 2 ${'$'}\times${'$'} 3 = 6, ${'$'}\pi${'$'} ${'$'}\approx${'$'} 3.14

                Logic: ${'$'}\forall${'$'} x ${'$'}\in${'$'} S, x ${'$'}\geq${'$'} 0

                Greek: ${'$'}\alpha${'$'}, ${'$'}\beta${'$'}, ${'$'}\gamma${'$'}, ${'$'}\Delta${'$'}

                Bare: \rightarrow works too, and \infty
            """.trimIndent(),
        )
    }
}
