package com.kernel.ai.feature.chat

import android.util.Log
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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

/** A single item in a bullet list, carrying its nesting [depth] (0 = top level). */
private data class BulletItem(val depth: Int, val text: String)

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    /** Items are [BulletItem] values; [depth] drives indentation in the Composable. */
    data class BulletList(val items: List<BulletItem>) : MarkdownBlock()
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
    "\\varepsilon" to "ε",
    "\\zeta" to "ζ",
    "\\eta" to "η",
    "\\theta" to "θ",
    "\\vartheta" to "ϑ",
    "\\iota" to "ι",
    "\\kappa" to "κ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\nu" to "ν",
    "\\xi" to "ξ",
    "\\pi" to "π",
    "\\rho" to "ρ",
    "\\sigma" to "σ",
    "\\tau" to "τ",
    "\\upsilon" to "υ",
    "\\phi" to "φ",
    "\\varphi" to "φ",
    "\\chi" to "χ",
    "\\psi" to "ψ",
    "\\omega" to "ω",
    "\\Gamma" to "Γ",
    "\\Delta" to "Δ",
    "\\Theta" to "Θ",
    "\\Lambda" to "Λ",
    "\\Xi" to "Ξ",
    "\\Pi" to "Π",
    "\\Sigma" to "Σ",
    "\\Phi" to "Φ",
    "\\Psi" to "Ψ",
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
    "\\dots" to "…",
    "\\ldots" to "…",
    "\\cdots" to "⋯",
    "\\vdots" to "⋮",
    "\\ddots" to "⋱",
    "\\checkmark" to "✓",
    "\\dagger" to "†",
    "\\ell" to "ℓ",
    "\\hbar" to "ℏ",
    "\\Re" to "ℜ",
    "\\Im" to "ℑ",

    // Math function names (rendered as plain text)
    "\\arccos" to "arccos",
    "\\arcsin" to "arcsin",
    "\\arctan" to "arctan",
    "\\cos" to "cos",
    "\\cosh" to "cosh",
    "\\cot" to "cot",
    "\\csc" to "csc",
    "\\deg" to "deg",
    "\\det" to "det",
    "\\exp" to "exp",
    "\\gcd" to "gcd",
    "\\inf" to "inf",
    "\\lim" to "lim",
    "\\liminf" to "lim inf",
    "\\limsup" to "lim sup",
    "\\ln" to "ln",
    "\\log" to "log",
    "\\max" to "max",
    "\\min" to "min",
    "\\sec" to "sec",
    "\\sin" to "sin",
    "\\sinh" to "sinh",
    "\\sup" to "sup",
    "\\tan" to "tan",
    "\\tanh" to "tanh",

    // Spacing
    "\\quad" to "  ",
    "\\qquad" to "    ",
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

// ── Structural LaTeX handling ──────────────────────────────────────────────────

/**
 * Finds the index of the closing brace `}` that matches the opening brace `{`
 * at [openIndex]. Returns -1 if [openIndex] doesn't point to `{` or no match is found.
 */
private fun findMatchingBrace(text: String, openIndex: Int): Int {
    if (openIndex >= text.length || text[openIndex] != '{') return -1
    var depth = 0
    for (i in openIndex until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

/**
 * Returns true when [expr] contains operators that would be ambiguous without
 * parentheses in a fraction representation like `a/b`.
 */
private fun needsParens(expr: String): Boolean =
    expr.contains('+') || expr.contains('-') || expr.contains('*') || expr.contains('/') || expr.contains(' ')

/**
 * Converts `\frac{numerator}{denominator}` into `numerator/denominator` (or
 * `(numerator)/(denominator)` when either part contains operators). Supports
 * nested braces; processes recursively until stable.
 */
private fun processFractions(text: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val idx = text.indexOf("\\frac", i)
        if (idx == -1) { sb.append(text, i, text.length); break }
        sb.append(text, i, idx)
        val braceStart = idx + 5
        if (braceStart < text.length && text[braceStart] == '{') {
            val numEnd = findMatchingBrace(text, braceStart)
            if (numEnd != -1 && numEnd + 1 < text.length && text[numEnd + 1] == '{') {
                val denEnd = findMatchingBrace(text, numEnd + 1)
                if (denEnd != -1) {
                    val num = text.substring(braceStart + 1, numEnd)
                    val den = text.substring(numEnd + 2, denEnd)
                    val numStr = if (needsParens(num)) "($num)" else num
                    val denStr = if (needsParens(den)) "($den)" else den
                    sb.append("$numStr/$denStr")
                    i = denEnd + 1
                    continue
                }
            }
        }
        sb.append("\\frac")
        i = braceStart
    }
    val result = sb.toString()
    // Guard against infinite recursion: if the string didn't change, no further
    // progress is possible (malformed input like \frac{a}b — bail out).
    return if (result != text && result.contains("\\frac")) processFractions(result) else result
}

/**
 * Converts `\sqrt{content}` into `√(content)`.
 * Bare `\sqrt` (without braces) is handled by the simple replacement map.
 */
private fun processSqrtBraces(text: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val idx = text.indexOf("\\sqrt", i)
        if (idx == -1) { sb.append(text, i, text.length); break }
        sb.append(text, i, idx)
        val braceStart = idx + 5
        if (braceStart < text.length && text[braceStart] == '{') {
            val braceEnd = findMatchingBrace(text, braceStart)
            if (braceEnd != -1) {
                sb.append("√(${text.substring(braceStart + 1, braceEnd)})")
                i = braceEnd + 1
                continue
            }
        }
        sb.append("\\sqrt")
        i = braceStart
    }
    return sb.toString()
}

/** Strips `\text{…}`, `\mathrm{…}`, `\mathbb{…}`, `\operatorname{…}` etc. to plain content. */
private val EXTRACT_CONTENT_REGEX = Regex(
    """\\(?:text|mathrm|mathbb|mathcal|mathbf|mathit|textbf|textit|operatorname|overline|underline|hat|bar|vec|dot|tilde)\{([^}]*)\}""",
)

private fun processTextCommands(text: String): String =
    EXTRACT_CONTENT_REGEX.replace(text) { it.groupValues[1] }

/** Runs all structural LaTeX transformations (fractions, sqrt, text commands). */
private fun processStructuralLatex(text: String): String =
    processTextCommands(processSqrtBraces(processFractions(text)))

// ── \left / \right removal ─────────────────────────────────────────────────────

private val LEFT_DELIM_REGEX  = Regex("""\\left(?![A-Za-z])""")
private val RIGHT_DELIM_REGEX = Regex("""\\right(?![A-Za-z])""")

/** Removes `\left` and `\right` delimiters, leaving the delimiter character intact. */
private fun processLeftRight(text: String): String =
    text.replace(LEFT_DELIM_REGEX, "").replace(RIGHT_DELIM_REGEX, "")

// ── Subscript / superscript Unicode conversion ─────────────────────────────────

private val BRACED_SUPERSCRIPT_REGEX = Regex("""\^\{([^}]*)\}""")
private val SINGLE_SUPERSCRIPT_REGEX = Regex("""\^([A-Za-z0-9])""")
private val BRACED_SUBSCRIPT_REGEX   = Regex("""_\{([^}]*)\}""")
private val SINGLE_SUBSCRIPT_REGEX   = Regex("""_(\d)(?!\w)""")

private val SUPERSCRIPT_MAP = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
    'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
    'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
    'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
    'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
)

private val SUBSCRIPT_MAP = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ',
)

/** Converts every character to its Unicode superscript form, or returns null if any character lacks one. */
private fun toSuperscript(text: String): String? {
    val mapped = text.map { SUPERSCRIPT_MAP[it] ?: return null }
    return mapped.joinToString("")
}

/** Converts every character to its Unicode subscript form, or returns null if any character lacks one. */
private fun toSubscript(text: String): String? {
    val mapped = text.map { SUBSCRIPT_MAP[it] ?: return null }
    return mapped.joinToString("")
}

/**
 * Converts `^{content}` and `_{content}` to Unicode super-/subscripts where possible.
 * Falls back to `^(content)` / `_(content)` when Unicode equivalents aren't available
 * for every character. Single-character `^c` is also handled for superscripts.
 */
private fun processSubSuperscripts(text: String): String {
    var result = text
    result = BRACED_SUPERSCRIPT_REGEX.replace(result) { m ->
        val content = m.groupValues[1]
        toSuperscript(content) ?: "^(${content})"
    }
    result = SINGLE_SUPERSCRIPT_REGEX.replace(result) { m ->
        toSuperscript(m.groupValues[1]) ?: m.value
    }
    result = BRACED_SUBSCRIPT_REGEX.replace(result) { m ->
        val content = m.groupValues[1]
        toSubscript(content) ?: "_(${content})"
    }
    result = SINGLE_SUBSCRIPT_REGEX.replace(result) { m ->
        toSubscript(m.groupValues[1]) ?: m.value
    }
    return result
}

// ── Math delimiter stripping ───────────────────────────────────────────────────

private val DISPLAY_MATH_REGEX = Regex("""\$\$(.+?)\$\$""")
private val INLINE_MATH_REGEX  = Regex("""\$(.+?)\$""")

/**
 * Strips `$$…$$` (display math) and `$…$` (inline math) delimiters, keeping their
 * content. `$$` is processed first so it isn't mistakenly split into two `$` pairs.
 */
private fun stripMathDelimiters(text: String): String {
    var result = text
    result = DISPLAY_MATH_REGEX.replace(result) { it.groupValues[1].trim() }
    result = INLINE_MATH_REGEX.replace(result) { it.groupValues[1] }
    return result
}

// ── LaTeX brace cleanup ────────────────────────────────────────────────────────

private val BARE_BRACE_REGEX = Regex("""\{([^{}]*)\}""")

/** Removes remaining bare LaTeX grouping braces `{content}` → `content`. Loops
 *  until stable to handle nested braces left by partial structural parsing. */
private fun cleanupLatexArtifacts(text: String): String {
    var result = text
    var previous: String
    do {
        previous = result
        result = BARE_BRACE_REGEX.replace(result) { it.groupValues[1] }
    } while (result != previous)
    result = result.replace("\\\\", "\n")
    return result
}

// ── Core conversion ────────────────────────────────────────────────────────────

/**
 * Replaces LaTeX commands with Unicode equivalents, skipping code fences.
 *
 * Processing pipeline per non-code line:
 *  1. Structural LaTeX: `\frac{a}{b}` → `a/b`, `\sqrt{x}` → `√(x)`, `\text{…}` → content
 *  2. Dollar-wrapped symbol replacements: `$\rightarrow$` → `→`
 *  3. Bare symbol replacements: `\rightarrow` → `→` (word-boundary guarded)
 *  4. `\left` / `\right` removal
 *  5. Subscript / superscript Unicode conversion
 *  6. `$` / `$$` math delimiter stripping
 *  7. Remaining brace cleanup
 *
 * Lines inside fenced code blocks (``` … ```) are left untouched.
 * Standalone `$$` lines (display-math delimiters) are removed entirely.
 */
internal fun convertLatexToUnicode(text: String): String {
    val lines = text.lines()
    var inCodeFence = false
    val processed = mutableListOf<String>()
    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            inCodeFence = !inCodeFence
            processed.add(line)
        } else if (inCodeFence) {
            processed.add(line)
        } else if (line.trim() == "$$") {
            // Display-math delimiter line — drop it
        } else {
            processed.add(convertLatexLine(line))
        }
    }
    return processed.joinToString("\n")
}

private fun convertLatexLine(line: String): String {
    var result = line

    // 1. Structural patterns (must run before simple replacements consume commands)
    result = processStructuralLatex(result)

    // 2. Dollar-wrapped single-command replacements: $\cmd$ → unicode
    for ((regex, unicode) in DOLLAR_WRAPPED_REPLACEMENTS) {
        result = regex.replace(result, Regex.escapeReplacement(unicode))
    }

    // 3. Bare command replacements: \cmd → unicode (longest-first, word-boundary guarded)
    for ((regex, unicode) in BARE_LATEX_REPLACEMENTS) {
        result = regex.replace(result, Regex.escapeReplacement(unicode))
    }

    // 4. \left / \right delimiter removal
    result = processLeftRight(result)

    // 5. Subscript / superscript → Unicode
    result = processSubSuperscripts(result)

    // 6. Strip remaining $…$ and $$…$$ math delimiters
    result = stripMathDelimiters(result)

    // 7. Clean up leftover LaTeX braces
    result = cleanupLatexArtifacts(result)

    return result
}

// ── Block-level regex patterns ─────────────────────────────────────────────────

private val HEADER_REGEX          = Regex("^(#{1,6})\\s+(.*)")
private val BULLET_REGEX          = Regex("^[-*+]\\s+(.*)")
/** Matches indented list items: 1+ leading spaces/tabs, then a list marker, then content. */
private val INDENTED_BULLET_REGEX = Regex("^([ \t]+)[-*+][ \t]+(.*)")
private val ORDERED_REGEX         = Regex("^\\d+\\.\\s+(.*)")
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
        if (BULLET_REGEX.matches(line) || INDENTED_BULLET_REGEX.matches(line)) {
            val items = mutableListOf<BulletItem>()
            while (i < lines.size) {
                val l = lines[i]
                val topMatch = BULLET_REGEX.matchEntire(l)
                if (topMatch != null) {
                    items.add(BulletItem(0, topMatch.groupValues[1]))
                    i++
                    continue
                }
                val indentedMatch = INDENTED_BULLET_REGEX.matchEntire(l)
                if (indentedMatch != null) {
                    val rawIndent = indentedMatch.groupValues[1]
                    val depth = if (rawIndent.contains('\t')) {
                        // Count tabs as indent levels
                        rawIndent.count { it == '\t' }.coerceAtLeast(1)
                    } else {
                        // Space-only: every 2 spaces = 1 level
                        (rawIndent.length / 2).coerceAtLeast(1)
                    }
                    items.add(BulletItem(depth, indentedMatch.groupValues[2]))
                    i++
                    continue
                }
                break
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
            if (INDENTED_BULLET_REGEX.matches(pl)) break
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

private data class InlineSpan(
    val start: Int,
    val end: Int,
    val type: String,
    val content: String,
    /** Text displayed to the user (defaults to [content]). For markdown links `[text](url)`,
     *  [displayText] holds the link label while [content] stores the URL. */
    val displayText: String = content,
)

/** Matches markdown links `[text](url)` with one level of nested parens
 *  (e.g. Wikipedia URLs like `https://en.wikipedia.org/wiki/Rust_(programming_language)`). */
private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)]\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)")

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

    // Markdown links: [text](url) — parsed before raw URL detection so the full
    // `[…](…)` range is consumed and not double-matched by Patterns.WEB_URL below.
    MARKDOWN_LINK_REGEX.findAll(text).forEach { m ->
        spans.add(
            InlineSpan(
                start = m.range.first,
                end = m.range.last + 1,
                type = "URL",
                content = m.groupValues[2],      // the URL
                displayText = m.groupValues[1],  // the link label
            )
        )
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
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(span.displayText) }
                    pop()
                }
            }
            cursor = span.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

// ── Block composables ──────────────────────────────────────────────────────────

/** Opens [url] safely — prepends https:// if no scheme present, blocks non-HTTP(S) schemes,
 *  and swallows unresolvable URIs. */
private fun openUrlSafely(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    val uri = android.net.Uri.parse(safeUrl)
    if (uri.scheme != "http" && uri.scheme != "https") {
        android.util.Log.w("MarkdownRenderer", "Blocked non-HTTP URI: $safeUrl")
        return
    }
    try {
        uriHandler.openUri(safeUrl)
    } catch (e: Exception) {
        android.util.Log.w("MarkdownRenderer", "Could not open URL: $safeUrl — ${e.message}")
    }
}

@Composable
private fun BlockContent(
    block: MarkdownBlock,
    baseStyle: TextStyle,
    onLongPress: (() -> Unit)? = null,
) {
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
            LinkableText(
                text = annotated,
                style = baseStyle,
                uriHandler = uriHandler,
                onLongPress = onLongPress,
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
            LinkableText(
                text = annotated,
                style = headingStyle,
                uriHandler = uriHandler,
                onLongPress = onLongPress,
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
                LinkableText(
                    text = annotated,
                    style = baseStyle.copy(fontStyle = FontStyle.Italic, color = onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    uriHandler = uriHandler,
                    onLongPress = onLongPress,
                )
            }
        }

        // ── Bullet list ────────────────────────────────────────────────────────
        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEach { item ->
                    // • for depth 0 (top-level), ◦ for all sub-levels
                    val bullet    = if (item.depth == 0) "•" else "◦"
                    val annotated = remember(item, surfaceVariant, linkColor) {
                        renderInlineSpans("$bullet ${item.text}", surfaceVariant, linkColor)
                    }
                    LinkableText(
                        text      = annotated,
                        style     = baseStyle,
                        uriHandler = uriHandler,
                        onLongPress = onLongPress,
                        modifier  = Modifier.padding(start = 16.dp * item.depth),
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
                    LinkableText(
                        text = annotated,
                        style = baseStyle,
                        uriHandler = uriHandler,
                        onLongPress = onLongPress,
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
 * Text composable that supports both tappable URL annotations and long-press gestures.
 *
 * Unlike [ClickableText], this uses [BasicText] with [detectTapGestures] so that the
 * parent long-press handler fires reliably — [ClickableText] internally consumes all
 * pointer events, preventing parent gesture detectors from working.
 */
@Composable
private fun LinkableText(
    text: AnnotatedString,
    style: TextStyle,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    // BasicText doesn't participate in the Material theme hierarchy (unlike Text).
    // Ensure text color falls back to LocalContentColor for correct dark-mode rendering.
    val contentColor = androidx.compose.material3.LocalContentColor.current
    val mergedStyle = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) {
        style.copy(color = contentColor)
    } else style
    BasicText(
        text = text,
        style = mergedStyle,
        modifier = modifier.pointerInput(text, onLongPress, uriHandler) {
            detectTapGestures(
                onTap = { pos ->
                    layoutResult.value?.let { result ->
                        val offset = result.getOffsetForPosition(pos)
                        text.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { openUrlSafely(uriHandler, it.item) }
                    }
                },
                onLongPress = if (onLongPress != null) {
                    { _ -> onLongPress() }
                } else null,
            )
        },
        onTextLayout = { layoutResult.value = it },
    )
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
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg    = MaterialTheme.colorScheme.surfaceVariant

    // `horizontalScroll` passes unbounded width constraints — `weight()` cannot be used
    // inside such a container (causes crash). Use `defaultMinSize` so each cell wraps its
    // content while remaining at least 80 dp wide. `IntrinsicSize.Min` on each Row still
    // synchronises cell heights within the same logical row.
    val cellModifier = Modifier
        .defaultMinSize(minWidth = 80.dp)
        .fillMaxHeight()
        .border(0.5.dp, borderColor)
        .padding(horizontal = 8.dp, vertical = 4.dp)

    Column(modifier.horizontalScroll(rememberScrollState()).border(1.dp, borderColor)) {
        // Header row
        Row(
            Modifier
                .background(headerBg)
                .height(IntrinsicSize.Min)
        ) {
            headers.forEach { header ->
                Text(
                    text     = header,
                    style    = baseStyle.copy(fontWeight = FontWeight.Bold),
                    modifier = cellModifier,
                )
            }
        }
        // Data rows
        rows.forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                headers.indices.forEach { idx ->
                    Text(
                        text     = row.getOrElse(idx) { "" },
                        style    = baseStyle,
                        modifier = cellModifier,
                    )
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
 * @param text        Raw Markdown text (may include model artefact tags).
 * @param modifier    [Modifier] applied to the outer [Column].
 * @param style       Base [TextStyle] for body text; headings override this with Material 3 type scale.
 * @param onLongPress Optional callback invoked when the user long-presses any text block.
 *                     Used by [MessageBubble][ChatScreen] to show the "Copy message" context menu.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLongPress: (() -> Unit)? = null,
) {
    val cleaned = remember(text) { convertLatexToUnicode(stripSystemTags(text)) }
    val blocks  = remember(cleaned) { parseBlocks(cleaned) }

    Column(
        modifier           = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            BlockContent(block = block, baseStyle = style, onLongPress = onLongPress)
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

                Limit: ${'$'}\lim_{x \to 0} \frac{\sin(x)}{x} = 1${'$'}

                Quadratic: ${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}

                Greek: ${'$'}\alpha${'$'}, ${'$'}\beta${'$'}, ${'$'}\gamma${'$'}, ${'$'}\Delta${'$'}

                Arrows: A ${'$'}\rightarrow${'$'} B ${'$'}\Rightarrow${'$'} C

                Superscript: x^2 + y^2 = z^2
            """.trimIndent(),
        )
    }
}

@Preview(showBackground = true, name = "Markdown — nested bullets (#131)")
@Composable
private fun MarkdownNestedBulletsPreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            text = """
                - Top level one
                  * Nested sub-item A
                  * Nested sub-item B
                - Top level two
                    * Deeply nested item
                - Top level three
            """.trimIndent(),
        )
    }
}

@Preview(showBackground = true, name = "Markdown — table alignment (#130)")
@Composable
private fun MarkdownTableAlignmentPreview() {
    KernelAITheme {
        MarkdownContent(
            modifier = Modifier.padding(16.dp),
            text = """
                | Framework       | Language   | Stars |
                |-----------------|------------|-------|
                | Jetpack Compose | Kotlin     | 5k    |
                | SwiftUI         | Swift      | 12k   |
                | Flutter         | Dart       | 160k  |
            """.trimIndent(),
        )
    }
}
