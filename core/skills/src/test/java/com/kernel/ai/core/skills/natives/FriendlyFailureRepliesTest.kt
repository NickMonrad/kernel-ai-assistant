package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FriendlyFailureRepliesTest {
    @Test
    fun `unit conversion names the unsupported unit`() {
        assertEquals(
            "I don't know the unit 'parsecs'.",
            FriendlyFailureReplies.unitConversion("Unsupported unit 'parsecs'"),
        )
    }

    @Test
    fun `unit conversion explains units that measure different things`() {
        assertEquals(
            "I can't convert kilograms to kilometers; they measure different things.",
            FriendlyFailureReplies.unitConversion("Cannot convert kilograms to kilometers"),
        )
    }

    @Test
    fun `unit conversion explains temperatures below absolute zero`() {
        assertEquals(
            "That temperature is below absolute zero, the lowest possible temperature.",
            FriendlyFailureReplies.unitConversion("Temperature cannot be below absolute zero"),
        )
        assertEquals(
            "Kelvin can't be negative. Absolute zero is 0 K.",
            FriendlyFailureReplies.unitConversion("Kelvin value must be non-negative"),
        )
    }

    @Test
    fun `unit conversion explains negative and oversized values`() {
        assertEquals(
            "I can't convert a negative value.",
            FriendlyFailureReplies.unitConversion("Conversion value must be non-negative"),
        )
        assertEquals(
            "That number is too long for me to convert.",
            FriendlyFailureReplies.unitConversion("Conversion value is too long"),
        )
    }

    @Test
    fun `unit conversion gives the generic reply for missing or unparseable input`() {
        val messages = listOf(
            "No conversion value provided",
            "No source unit provided",
            "No target unit provided",
            "Invalid conversion value 'abc'",
        )
        for (message in messages) {
            assertEquals(FriendlyFailureReplies.UNIT_CONVERSION_GENERIC, FriendlyFailureReplies.unitConversion(message), message)
        }
    }

    @Test
    fun `unrecognised unit conversion messages fall back to the generic reply`() {
        assertEquals(
            FriendlyFailureReplies.UNIT_CONVERSION_GENERIC,
            FriendlyFailureReplies.unitConversion("Something the evaluator never says"),
        )
    }

    @Test
    fun `arithmetic points at the token it did not expect`() {
        assertEquals(
            "I didn't expect ')' in that calculation.",
            FriendlyFailureReplies.arithmetic("Unexpected token ')'"),
        )
        assertEquals(
            "I couldn't follow that calculation near 'x y'.",
            FriendlyFailureReplies.arithmetic("Unexpected token near 'x y'"),
        )
    }

    @Test
    fun `arithmetic explains brackets and function arguments`() {
        assertEquals(
            "A bracket is missing its closing ')'.",
            FriendlyFailureReplies.arithmetic("Missing closing ')' after sqrt arguments"),
        )
        assertEquals(
            "sqrt needs brackets around its value, like sqrt(4).",
            FriendlyFailureReplies.arithmetic("Expected '(' after sqrt"),
        )
        assertEquals(
            "abs() takes exactly one value.",
            FriendlyFailureReplies.arithmetic("abs() takes exactly 1 argument"),
        )
        assertEquals(
            "round() takes one or two values.",
            FriendlyFailureReplies.arithmetic("round() takes 1 or 2 arguments"),
        )
        assertEquals(
            "round() needs a whole number of decimal places.",
            FriendlyFailureReplies.arithmetic("round() scale must be a whole number"),
        )
        assertEquals(
            "I don't know a function called 'foo'.",
            FriendlyFailureReplies.arithmetic("Unsupported function 'foo'"),
        )
        assertEquals(
            "'1.2.3' isn't a number I can use.",
            FriendlyFailureReplies.arithmetic("Invalid number '1.2.3'"),
        )
    }

    @Test
    fun `arithmetic explains division, domain and size limits`() {
        assertEquals("I can't divide by zero.", FriendlyFailureReplies.arithmetic("Division by zero is undefined"))
        assertEquals(
            "I can't work out a remainder when dividing by zero.",
            FriendlyFailureReplies.arithmetic("Modulo by zero is undefined"),
        )
        assertEquals(
            "I can't take the square root of a negative number.",
            FriendlyFailureReplies.arithmetic("sqrt() is undefined for negative numbers"),
        )
        assertEquals(
            "I can only raise numbers to whole-number powers.",
            FriendlyFailureReplies.arithmetic("Exponent must be a whole number"),
        )
        assertEquals(
            "That power is too large for me to work out.",
            FriendlyFailureReplies.arithmetic("Exponent is too large"),
        )
        assertEquals(
            "That expression is too long for me to work out.",
            FriendlyFailureReplies.arithmetic("Expression is too long"),
        )
    }

    @Test
    fun `arithmetic gives the generic reply for missing input and unrecognised messages`() {
        assertEquals(FriendlyFailureReplies.ARITHMETIC_GENERIC, FriendlyFailureReplies.arithmetic("No expression provided"))
        assertEquals(FriendlyFailureReplies.ARITHMETIC_GENERIC, FriendlyFailureReplies.arithmetic("Something new"))
    }

    @Test
    fun `cooking conversion names an unsupported unit and keeps the supported list`() {
        assertEquals(
            "I don't know the cooking unit 'stone'. Use g, kg, oz, lb, mL, L, tsp, tbsp, AU tbsp, cups, fl oz, pints, quarts, or gallons.",
            FriendlyFailureReplies.cookingConversion(
                "Unsupported cooking source unit 'stone'. Use g, kg, oz, lb, mL, L, tsp, tbsp, AU tbsp, cups, fl oz, pints, quarts, or gallons.",
            ),
        )
    }

    @Test
    fun `cooking conversion explains an unsupported ingredient and keeps the staples hint`() {
        assertEquals(
            "I don't have a built-in cooking conversion for 'unicorn dust'. " +
                "I can only do built-in cooking conversions for a small set of staples such as butter and sugar.",
            FriendlyFailureReplies.cookingConversion(
                "Unsupported cooking ingredient 'unicorn dust'. " +
                    "I can only do built-in cooking conversions for a small set of staples such as butter and sugar.",
            ),
        )
        assertEquals(
            "I don't have a built-in cooking conversion for 'unicorn dust'.",
            FriendlyFailureReplies.cookingConversion("Unsupported cooking ingredient 'unicorn dust'."),
        )
    }

    @Test
    fun `cooking conversion explains amount limits and gives the generic reply for missing or unparseable input`() {
        assertEquals(
            "I can't convert a negative amount.",
            FriendlyFailureReplies.cookingConversion("Cooking conversion amount must be non-negative"),
        )
        assertEquals(
            "That amount is too long for me to convert.",
            FriendlyFailureReplies.cookingConversion("Cooking conversion amount is too long"),
        )
        for (message in listOf(
            "No cooking conversion amount provided",
            "Could not parse cooking conversion amount 'abc'",
            "No cooking ingredient provided",
            "Something new",
        )) {
            assertEquals(FriendlyFailureReplies.COOKING_GENERIC, FriendlyFailureReplies.cookingConversion(message), message)
        }
    }

    @Test
    fun `currency conversion explains an unrecognised currency`() {
        assertEquals(
            "I can't tell which source currency 'galactic credits' is. " +
                "Use an ISO code like USD or a full name like Australian dollars.",
            FriendlyFailureReplies.currencyConversion(
                "Unsupported or ambiguous source currency 'galactic credits'. " +
                    "Use an ISO code like USD or a full name like Australian dollars.",
            ),
        )
    }

    @Test
    fun `currency conversion explains a stale rate`() {
        assertEquals(
            "The latest AUD to NZD rate I can get is from 2026-05-01, which is too old to use.",
            FriendlyFailureReplies.currencyConversion(
                "Latest available AUD to NZD rate is from 2026-05-01, which is too stale to use.",
            ),
        )
    }

    @Test
    fun `currency conversion explains amount limits`() {
        assertEquals(
            "I can't convert a negative amount.",
            FriendlyFailureReplies.currencyConversion("Currency amount must be non-negative"),
        )
        assertEquals(
            "That amount is too long for me to convert.",
            FriendlyFailureReplies.currencyConversion("Currency amount is too long"),
        )
    }

    @Test
    fun `currency conversion gives the generic reply for missing or unparseable input`() {
        for (message in listOf(
            "No currency amount provided",
            "No source currency provided",
            "No target currency provided",
            "Could not parse currency amount 'abc'",
        )) {
            assertEquals(FriendlyFailureReplies.CURRENCY_GENERIC, FriendlyFailureReplies.currencyConversion(message), message)
        }
    }

    @Test
    fun `currency conversion says rates are unavailable for rate and network failures`() {
        assertEquals(
            FriendlyFailureReplies.CURRENCY_UNAVAILABLE,
            FriendlyFailureReplies.currencyConversion("Could not find a AUD to NZD exchange rate."),
        )
        assertEquals(
            FriendlyFailureReplies.CURRENCY_UNAVAILABLE,
            FriendlyFailureReplies.currencyConversion("socket timeout"),
        )
    }
}
