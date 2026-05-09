package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnitConversionEvaluatorTest {
    @Test
    fun `convert weight exact metric conversion`() {
        val result = UnitConversionEvaluator.convert("500", "grams", "kg")

        assertEquals("500", result.inputValue.toPlainString())
        assertEquals("0.5", result.outputValue.toPlainString())
        assertEquals(UnitConversionEvaluator.SupportedUnit.GRAM, result.fromUnit)
        assertEquals(UnitConversionEvaluator.SupportedUnit.KILOGRAM, result.toUnit)
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert distance exact mile to kilometer conversion`() {
        val result = UnitConversionEvaluator.convert("5", "miles", "km")

        assertEquals("8.04672", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert weight marks non terminating results approximate`() {
        val result = UnitConversionEvaluator.convert("5", "kg", "pounds")

        assertEquals("11.02311311", result.outputValue.toPlainString())
        assertTrue(result.isApproximate)
    }

    @Test
    fun `convert speed supports per hour aliases`() {
        val result = UnitConversionEvaluator.convert("60", "mph", "kilometers per hour")

        assertEquals("96.56064", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert speed accepts spaced slash aliases`() {
        val result = UnitConversionEvaluator.convert("60", "km / h", "mph")

        assertEquals("37.28227153", result.outputValue.toPlainString())
        assertTrue(result.isApproximate)
    }

    @Test
    fun `convert rejects cross category conversions`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEvaluator.convert("5", "miles", "kilograms")
        }

        assertEquals("Cannot convert miles to kilograms", error.message)
    }

    @Test
    fun `convert rejects unsupported target units`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEvaluator.convert("5", "miles", "yards")
        }

        assertEquals("Unsupported unit 'yards'", error.message)
    }

    @Test
    fun `convert rejects negative values`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEvaluator.convert("-5", "miles", "km")
        }

        assertEquals("Conversion value must be non-negative", error.message)
    }
}
