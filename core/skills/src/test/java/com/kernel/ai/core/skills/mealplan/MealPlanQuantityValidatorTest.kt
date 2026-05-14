package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MealPlanQuantityValidatorTest {
    private val validator = MealPlanQuantityValidator()

    @Test
    fun `validateAndNormalize normalizes plausible metric ingredient`() {
        val recipe = RecipeDraft(
            title = "Chicken stir-fry",
            servings = 4,
            ingredients = listOf(
                RecipeDraftIngredient(
                    originalText = "500 g chicken breast, sliced",
                    amount = "500",
                    unit = "g",
                    item = "chicken breast",
                    note = "sliced",
                ),
            ),
            methodSteps = listOf(
                RecipeDraftMethodStep(1, "Heat oil in a pan."),
                RecipeDraftMethodStep(2, "Cook the chicken until browned."),
            ),
        )

        val groceries = validator.validateAndNormalize(recipe)

        assertEquals(1, groceries.size)
        assertEquals(GroceryNormalizationStatus.NORMALIZED, groceries.single().normalizationStatus)
        assertEquals("500 g chicken breast, sliced", groceries.single().displayText)
    }

    @Test
    fun `validateAndNormalize rejects absurd kilogram quantity`() {
        val recipe = RecipeDraft(
            title = "Chicken stir-fry",
            servings = 4,
            ingredients = listOf(
                RecipeDraftIngredient(
                    originalText = "200000 kg chicken breast",
                    amount = "200000",
                    unit = "kg",
                    item = "chicken breast",
                    note = null,
                ),
            ),
            methodSteps = listOf(
                RecipeDraftMethodStep(1, "Heat oil in a pan."),
                RecipeDraftMethodStep(2, "Cook the chicken until browned."),
            ),
        )

        assertThrows(MealPlanValidationException::class.java) {
            validator.validateAndNormalize(recipe)
        }
    }

    @Test
    fun `validateAndNormalize keeps ambiguous plausible lines opaque`() {
        val recipe = RecipeDraft(
            title = "Chicken stir-fry",
            servings = 4,
            ingredients = listOf(
                RecipeDraftIngredient(
                    originalText = "1 onion, sliced",
                    amount = null,
                    unit = null,
                    item = "onion",
                    note = "sliced",
                ),
            ),
            methodSteps = listOf(
                RecipeDraftMethodStep(1, "Heat oil in a pan."),
                RecipeDraftMethodStep(2, "Cook the chicken until browned."),
            ),
        )

        val groceries = validator.validateAndNormalize(recipe)

        assertEquals(GroceryNormalizationStatus.OPAQUE, groceries.single().normalizationStatus)
        assertEquals("1 onion, sliced", groceries.single().displayText)
    }
}
