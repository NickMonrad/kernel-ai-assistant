package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CookingConversionServiceTest {
    private val service = CookingConversionService()

    @Test
    fun `convert volume to mass using built in density`() {
        val result = service.convert(
            amountRaw = "3",
            fromUnitRaw = "tbsp",
            ingredientRaw = "butter",
            toUnitRaw = "g",
        )

        assertEquals(0, result.outputAmount.compareTo(BigDecimal("41.9999999985")))
        assertEquals("42", result.outputAmount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        assertEquals("butter", result.ingredientLabel)
        assertEquals(listOf("tablespoons are treated as 15 mL"), result.assumptionTexts)
        assertEquals(true, result.usedIngredientDensity)
    }

    @Test
    fun `convert respects australian tablespoon alias`() {
        val result = service.convert(
            amountRaw = "2",
            fromUnitRaw = "Australian tablespoon",
            ingredientRaw = "honey",
            toUnitRaw = "g",
        )

        assertEquals("54.4", result.outputAmount.toPlainString())
        assertEquals(listOf("Australian tablespoons are treated as 20 mL"), result.assumptionTexts)
    }

    @Test
    fun `convert mass to volume for supported pantry ingredient`() {
        val result = service.convert(
            amountRaw = "400",
            fromUnitRaw = "g",
            ingredientRaw = "plain flour",
            toUnitRaw = "cups",
        )

        assertEquals(0, result.outputAmount.compareTo(BigDecimal("2.6666666667")))
        assertEquals(listOf("cups are treated as 250 mL"), result.assumptionTexts)
    }

    @Test
    fun `convert keeps same category kitchen volume assumptions consistent`() {
        val result = service.convert(
            amountRaw = "3",
            fromUnitRaw = "tbsp",
            ingredientRaw = "butter",
            toUnitRaw = "mL",
        )

        assertEquals("45", result.outputAmount.toPlainString())
        assertEquals(listOf("tablespoons are treated as 15 mL"), result.assumptionTexts)
        assertEquals(false, result.usedIngredientDensity)
    }

    @Test
    fun `convert supports larger physical volume units for density based cooking conversions`() {
        val result = service.convert(
            amountRaw = "2",
            fromUnitRaw = "gallons",
            ingredientRaw = "milk",
            toUnitRaw = "kilogrammes",
        )

        assertEquals(0, result.outputAmount.compareTo(BigDecimal("7.797948275")))
        assertEquals("7.8", result.outputAmount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        assertEquals("milk", result.ingredientLabel)
        assertEquals(emptyList<String>(), result.assumptionTexts)
        assertEquals(true, result.usedIngredientDensity)
    }

    @Test
    fun `convert supports larger physical volume targets for density based cooking conversions`() {
        val result = service.convert(
            amountRaw = "400",
            fromUnitRaw = "g",
            ingredientRaw = "milk",
            toUnitRaw = "gallons",
        )

        assertEquals(0, result.outputAmount.compareTo(BigDecimal("0.1025910883")))
        assertEquals("0.1", result.outputAmount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        assertEquals("milk", result.ingredientLabel)
        assertEquals(emptyList<String>(), result.assumptionTexts)
        assertEquals(true, result.usedIngredientDensity)
    }

    @Test
    fun `convert rejects unknown ingredient`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            service.convert(
                amountRaw = "3",
                fromUnitRaw = "tbsp",
                ingredientRaw = "unicorn dust",
                toUnitRaw = "g",
            )
        }

        assertEquals(
            "Unsupported cooking ingredient 'unicorn dust'. I can only do built-in cooking conversions for a small set of staples such as butter, plain flour, sugar, rice, oats, milk, water, olive oil, honey, tomato paste, cheese, and lentils.",
            error.message,
        )
    }

    @Test
    fun `convert rejects unsupported units`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            service.convert(
                amountRaw = "3",
                fromUnitRaw = "oz",
                ingredientRaw = "butter",
                toUnitRaw = "g",
            )
        }

        assertEquals(
            "Unsupported cooking source unit 'oz'. Use g, kg, mL, L, tsp, tbsp, AU tbsp, cups, fl oz, pints, quarts, or gallons.",
            error.message,
        )
    }
}
