package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnitConversionEvaluatorTest {
    @Test
    fun `convert mass exact metric conversion`() {
        val result = UnitConversionEvaluator.convert("500", "grams", "kg")

        assertEquals("500", result.inputValue.toPlainString())
        assertEquals("0.5", result.outputValue.toPlainString())
        assertEquals(UnitConversionEvaluator.SupportedUnit.GRAM, result.fromUnit)
        assertEquals(UnitConversionEvaluator.SupportedUnit.KILOGRAM, result.toUnit)
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert length exact metric scaling`() {
        val result = UnitConversionEvaluator.convert("1.5", "m", "cm")

        assertEquals("150", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert length supports common imperial units`() {
        val result = UnitConversionEvaluator.convert("100", "meters", "yards")

        assertEquals("109.36132983", result.outputValue.toPlainString())
        assertTrue(result.isApproximate)
    }

    @Test
    fun `convert length exposes mixed feet and inches breakdown for inch target`() {
        val result = UnitConversionEvaluator.convert("189", "cm", "inches")

        assertEquals("74.40944882", result.outputValue.toPlainString())
        assertTrue(result.isApproximate)
        requireNotNull(result.mixedUnitBreakdown)
        assertEquals(UnitConversionEvaluator.SupportedUnit.FOOT, result.mixedUnitBreakdown.primaryUnit)
        assertEquals("6", result.mixedUnitBreakdown.primaryValue.toPlainString())
        assertEquals(UnitConversionEvaluator.SupportedUnit.INCH, result.mixedUnitBreakdown.secondaryUnit)
        assertEquals("2.40944882", result.mixedUnitBreakdown.secondaryValue.toPlainString())
    }

    @Test
    fun `convert length from total inches to centimeters stays exact enough for mixed input normalization`() {
        val result = UnitConversionEvaluator.convert("74", "inches", "cm")

        assertEquals("187.96", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert volume supports kitchen measures`() {
        val result = UnitConversionEvaluator.convert("1", "gallon", "liters")

        assertEquals("3.785411784", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
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
    fun `convert temperature supports exact freezing point conversions`() {
        val result = UnitConversionEvaluator.convert("32", "fahrenheit", "celsius")

        assertEquals("0", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert temperature allows negative values above absolute zero`() {
        val result = UnitConversionEvaluator.convert("-40", "celsius", "fahrenheit")

        assertEquals("-40", result.outputValue.toPlainString())
        assertFalse(result.isApproximate)
    }

    @Test
    fun `convert temperature marks non terminating results approximate`() {
        val result = UnitConversionEvaluator.convert("1", "fahrenheit", "celsius")

        assertEquals("-17.22222222", result.outputValue.toPlainString())
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
            UnitConversionEvaluator.convert("5", "miles", "parsecs")
        }

        assertEquals("Unsupported unit 'parsecs'", error.message)
    }

    @Test
    fun `convert rejects negative non temperature values`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEvaluator.convert("-5", "miles", "km")
        }

        assertEquals("Conversion value must be non-negative", error.message)
    }

    @Test
    fun `convert rejects temperatures below absolute zero`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UnitConversionEvaluator.convert("-500", "fahrenheit", "celsius")
        }

        assertEquals("Temperature cannot be below absolute zero", error.message)
    }
}
