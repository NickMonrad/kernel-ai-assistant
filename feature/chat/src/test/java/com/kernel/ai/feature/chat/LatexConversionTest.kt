package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the LaTeX-to-Unicode conversion pipeline in [MarkdownRenderer].
 *
 * Covers:
 *  - Simple symbol replacements (arrows, Greek letters, operators)
 *  - Math function names (\lim, \sin, \cos, etc.)
 *  - Structural patterns (\frac, \sqrt, \text)
 *  - Subscript / superscript Unicode conversion
 *  - Dollar-sign math delimiter stripping
 *  - Code-fence exclusion
 *  - Display-math ($$) line removal
 */
class LatexConversionTest {

    @Nested
    @DisplayName("Simple symbol replacements")
    inner class SimpleSymbols {

        @Test
        fun `bare arrow commands are converted`() {
            val input = "A \\rightarrow B \\leftarrow C"
            val result = convertLatexToUnicode(input)
            assertEquals("A → B ← C", result)
        }

        @Test
        fun `dollar-wrapped arrow is converted`() {
            val input = "A \$\\rightarrow\$ B"
            val result = convertLatexToUnicode(input)
            assertEquals("A → B", result)
        }

        @Test
        fun `Greek letters are converted`() {
            val input = "\\alpha + \\beta = \\gamma"
            val result = convertLatexToUnicode(input)
            assertEquals("α + β = γ", result)
        }

        @Test
        fun `math operators are converted`() {
            val input = "2 \\times 3 \\neq 5"
            val result = convertLatexToUnicode(input)
            assertEquals("2 × 3 ≠ 5", result)
        }

        @Test
        fun `word-boundary guard prevents partial matches`() {
            // \to should not match inside \top (handled by negative lookahead)
            val input = "\\top and \\to"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("\\top"), "\\top should be left intact")
            assertTrue(result.contains("→"), "\\to should be converted")
        }
    }

    @Nested
    @DisplayName("Math function names")
    inner class MathFunctions {

        @Test
        fun `lim is converted to plain text`() {
            val result = convertLatexToUnicode("\\lim_{x \\to 0}")
            assertTrue(result.contains("lim"), "\\lim should become lim")
            assertFalse(result.contains("\\lim"), "\\lim should not remain")
        }

        @Test
        fun `sin cos tan are converted`() {
            val input = "\\sin(x) + \\cos(x) + \\tan(x)"
            val result = convertLatexToUnicode(input)
            assertEquals("sin(x) + cos(x) + tan(x)", result)
        }

        @Test
        fun `log and ln are converted`() {
            val input = "\\log(x) and \\ln(x)"
            val result = convertLatexToUnicode(input)
            assertEquals("log(x) and ln(x)", result)
        }

        @Test
        fun `sinh does not conflict with sin`() {
            val input = "\\sinh(x) and \\sin(x)"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("sinh(x)"), "\\sinh should become sinh")
            assertTrue(result.contains("sin(x)"), "\\sin should become sin")
        }

        @Test
        fun `limsup and liminf are converted`() {
            val input = "\\limsup and \\liminf"
            val result = convertLatexToUnicode(input)
            assertEquals("lim sup and lim inf", result)
        }

        @Test
        fun `max min sup inf are converted`() {
            val input = "\\max(a, b) \\min(a, b)"
            val result = convertLatexToUnicode(input)
            assertEquals("max(a, b) min(a, b)", result)
        }
    }

    @Nested
    @DisplayName("Structural LaTeX: \\frac")
    inner class Fractions {

        @Test
        fun `simple fraction is converted`() {
            val result = convertLatexToUnicode("\\frac{a}{b}")
            assertEquals("a/b", result)
        }

        @Test
        fun `fraction with complex numerator gets parens`() {
            val result = convertLatexToUnicode("\\frac{a+b}{c}")
            assertEquals("(a+b)/c", result)
        }

        @Test
        fun `fraction with complex denominator gets parens`() {
            val result = convertLatexToUnicode("\\frac{a}{c+d}")
            assertEquals("a/(c+d)", result)
        }

        @Test
        fun `fraction with nested commands`() {
            val result = convertLatexToUnicode("\\frac{\\sin(x)}{x}")
            assertEquals("sin(x)/x", result)
        }

        @Test
        fun `nested fractions are handled`() {
            val result = convertLatexToUnicode("\\frac{\\frac{a}{b}}{c}")
            // Inner fraction first: \frac{a}{b} → a/b
            // Then outer: \frac{a/b}{c} → (a/b)/c (parens because / in numerator)
            assertEquals("(a/b)/c", result)
        }
    }

    @Nested
    @DisplayName("Structural LaTeX: \\sqrt")
    inner class SquareRoots {

        @Test
        fun `sqrt with braces is converted`() {
            val result = convertLatexToUnicode("\\sqrt{x}")
            assertEquals("√(x)", result)
        }

        @Test
        fun `sqrt with complex content`() {
            val result = convertLatexToUnicode("\\sqrt{x^2 + y^2}")
            // x^2 → x² via superscript processing
            assertTrue(result.startsWith("√("))
            assertTrue(result.contains("x"))
        }

        @Test
        fun `bare sqrt without braces becomes symbol`() {
            val result = convertLatexToUnicode("\\sqrt x")
            assertTrue(result.contains("√"), "bare \\sqrt should become √")
        }
    }

    @Nested
    @DisplayName("Text commands")
    inner class TextCommands {

        @Test
        fun `text command extracts content`() {
            val result = convertLatexToUnicode("\\text{hello world}")
            assertEquals("hello world", result)
        }

        @Test
        fun `mathrm command extracts content`() {
            val result = convertLatexToUnicode("\\mathrm{pH}")
            assertEquals("pH", result)
        }

        @Test
        fun `operatorname command extracts content`() {
            val result = convertLatexToUnicode("\\operatorname{argmax}")
            assertEquals("argmax", result)
        }
    }

    @Nested
    @DisplayName("Subscripts and superscripts")
    inner class SubSuperscripts {

        @Test
        fun `braced superscript with convertible chars`() {
            val result = convertLatexToUnicode("x^{2}")
            assertEquals("x²", result)
        }

        @Test
        fun `single-char superscript digit`() {
            val result = convertLatexToUnicode("x^2")
            assertEquals("x²", result)
        }

        @Test
        fun `single-char superscript letter`() {
            val result = convertLatexToUnicode("x^n")
            assertEquals("xⁿ", result)
        }

        @Test
        fun `braced subscript with all-convertible chars`() {
            val result = convertLatexToUnicode("a_{10}")
            assertEquals("a₁₀", result)
        }

        @Test
        fun `braced subscript falls back to parens for unconvertible chars`() {
            val result = convertLatexToUnicode("\\lim_{x → 0}")
            // x and 0 are convertible but → and space are not
            assertTrue(result.contains("lim"), "\\lim should be converted")
            assertTrue(result.contains("_("), "unconvertible subscript should use parens")
        }

        @Test
        fun `complex superscript falls back to parens`() {
            val result = convertLatexToUnicode("e^{i\\pi}")
            // After \pi → π, content is "iπ" — i has superscript but π does not
            // So fallback format is used: ^(iπ)
            assertEquals("e^(iπ)", result)
        }
    }

    @Nested
    @DisplayName("Math delimiter stripping")
    inner class MathDelimiters {

        @Test
        fun `inline math dollar signs are stripped`() {
            val result = convertLatexToUnicode("\$x + y\$")
            assertEquals("x + y", result)
        }

        @Test
        fun `display math dollar signs are stripped on single line`() {
            val result = convertLatexToUnicode("\$\$E = mc^2\$\$")
            assertTrue(result.contains("E"))
            assertTrue(result.contains("mc²"))
            assertFalse(result.contains("\$"), "dollar signs should be stripped")
        }

        @Test
        fun `standalone dollar-dollar lines are removed`() {
            val input = "Before\n\$\$\nE = mc^2\n\$\$\nAfter"
            val result = convertLatexToUnicode(input)
            assertFalse(result.contains("\$\$"), "standalone \$\$ lines should be removed")
            assertTrue(result.contains("E = mc²"))
            assertTrue(result.contains("Before"))
            assertTrue(result.contains("After"))
            // Verify the $$ delimiter lines are fully gone (3 content lines remain)
            val lines = result.lines().filter { it.isNotBlank() }
            assertEquals(3, lines.size, "Should have exactly 3 non-blank lines after $$ removal")
        }
    }

    @Nested
    @DisplayName("Code fence exclusion")
    inner class CodeFences {

        @Test
        fun `LaTeX inside fenced code is untouched`() {
            val input = "```\n\\alpha + \\beta\n```"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("\\alpha"), "LaTeX inside code fence should be untouched")
            assertTrue(result.contains("\\beta"), "LaTeX inside code fence should be untouched")
        }

        @Test
        fun `LaTeX outside code is converted while inside is not`() {
            val input = "\\alpha is alpha\n```\n\\beta stays\n```\n\\gamma is gamma"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("α"), "\\alpha outside code should be converted")
            assertTrue(result.contains("\\beta"), "\\beta inside code should stay")
            assertTrue(result.contains("γ"), "\\gamma outside code should be converted")
        }
    }

    @Nested
    @DisplayName("Left / right delimiters")
    inner class LeftRight {

        @Test
        fun `left and right parens are cleaned`() {
            val result = convertLatexToUnicode("\\left( x + y \\right)")
            assertEquals("( x + y )", result)
        }

        @Test
        fun `leftarrow is not affected by left removal`() {
            // \leftarrow should be converted to ← (via LATEX_TO_UNICODE), not stripped
            val result = convertLatexToUnicode("\\leftarrow")
            assertEquals("←", result)
        }
    }

    @Nested
    @DisplayName("Full expression integration")
    inner class FullExpressions {

        @Test
        fun `limit expression from the issue`() {
            val input = "\\lim_{x \\to 0} \\frac{\\sin(x)}{x} = 1"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("lim"), "should contain lim")
            assertTrue(result.contains("sin(x)/x"), "fraction should be converted")
            assertTrue(result.contains("→"), "\\to should be →")
            assertTrue(result.contains("= 1"), "should contain = 1")
            assertFalse(result.contains("\\"), "no backslash commands should remain")
        }

        @Test
        fun `dollar-wrapped limit expression`() {
            val input = "\$\\lim_{x \\to 0} \\frac{\\sin(x)}{x} = 1\$"
            val result = convertLatexToUnicode(input)
            assertFalse(result.contains("\$"), "dollar signs should be stripped")
            assertTrue(result.contains("lim"), "should contain lim")
            assertTrue(result.contains("sin(x)/x"), "fraction should be converted")
        }

        @Test
        fun `quadratic formula`() {
            val input = "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"
            val result = convertLatexToUnicode(input)
            assertTrue(result.contains("±"), "\\pm should be ±")
            assertTrue(result.contains("√"), "\\sqrt should be √")
            assertTrue(result.contains("b²"), "b^2 should have superscript")
            assertFalse(result.contains("\\frac"), "\\frac should be processed")
            assertFalse(result.contains("\\sqrt"), "\\sqrt should be processed")
        }

        @Test
        fun `spacing commands are converted`() {
            val input = "a \\quad b \\qquad c"
            val result = convertLatexToUnicode(input)
            assertFalse(result.contains("\\quad"), "\\quad should be converted to spaces")
        }

        @Test
        fun `brace cleanup removes remaining grouping braces`() {
            val result = convertLatexToUnicode("{x} + {y}")
            assertEquals("x + y", result)
        }
    }
}
