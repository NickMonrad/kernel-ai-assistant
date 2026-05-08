package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArithmeticExpressionEvaluatorTest {
    @Test
    fun `evaluate respects operator precedence`() {
        val result = ArithmeticExpressionEvaluator.evaluate("2 + 3 * 4")

        assertEquals("14", result.value.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `evaluate normalizes percent of phrases`() {
        val result = ArithmeticExpressionEvaluator.evaluate("what is 18.5% of 240")

        assertEquals("44.4", result.value.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `evaluate supports helper functions and modulo`() {
        val result = ArithmeticExpressionEvaluator.evaluate("round(sqrt((25^2)) + abs(-2.6) + (10 % 3), 2)")

        assertEquals("28.6", result.value.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `evaluate marks non terminating division as approximate`() {
        val result = ArithmeticExpressionEvaluator.evaluate("1 / 3")

        assertEquals("0.3333333333333333", result.value.toPlainString())
        assertTrue(result.isApproximate)
    }

    @Test
    fun `evaluate supports apostrophe free percentage suffixes`() {
        val result = ArithmeticExpressionEvaluator.evaluate("200 * 10%")

        assertEquals("20", result.value.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `evaluate rejects malformed expressions cleanly`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArithmeticExpressionEvaluator.evaluate("2 + )")
        }

        assertTrue(error.message!!.contains("Unexpected token") || error.message!!.contains("Missing"))
    }

    @Test
    fun `evaluate rejects division by zero`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArithmeticExpressionEvaluator.evaluate("10 / 0")
        }

        assertEquals("Division by zero is undefined", error.message)
    }
}
