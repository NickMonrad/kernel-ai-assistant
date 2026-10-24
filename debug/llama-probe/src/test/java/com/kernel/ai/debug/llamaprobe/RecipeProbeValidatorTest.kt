package com.kernel.ai.debug.llamaprobe

import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipeProbeValidatorTest {
    private val validator = RecipeProbeValidator(MealPlanJsonParser(), MealPlanQuantityValidator())

    @Test
    fun `malformed recipe reports parser category and exception class only`() {
        val result = validator.validate("not json")

        assertEquals(RecipeProbeFailureCategory.PARSER_EXCEPTION, result.category)
        assertEquals("MealPlanValidationException", result.exceptionClass)
        assertEquals(null, result.parsedIngredientCount)
        assertFalse(result.valid)
    }

    @Test
    fun `allowed seven ingredient output reports strict count mismatch`() {
        val data = ingredients() + FixtureIngredient("garlic", "1", "clove", "garlic")
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.INGREDIENT_COUNT_MISMATCH, result.category)
        assertEquals(7, result.parsedIngredientCount)
        assertEquals(7, result.normalizedIngredientCount)
        assertFalse(result.valid)
    }

    @Test
    fun `parsed recipe with opaque ingredient reports unnormalized category`() {
        val data = ingredients().toMutableList()
        data[0] = data[0].copy(amount = null)
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.PARSED_UNNORMALIZED_INGREDIENTS, result.category)
        assertEquals(6, result.parsedIngredientCount)
        assertEquals(5, result.normalizedIngredientCount)
        assertFalse(result.valid)
    }

    @Test
    fun `normalized recipe reports missing expected name separately`() {
        val data = ingredients().toMutableList()
        data[0] = data[0].copy(item = "poultry")
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.NORMALIZED_INGREDIENT_MISMATCH, result.category)
        assertEquals(6, result.normalizedIngredientCount)
        assertEquals(1, result.nameMismatchCount)
        assertEquals(0, result.unitMismatchCount)
        assertEquals(0, result.quantityMismatchCount)
    }

    @Test
    fun `normalized recipe reports unit mismatch`() {
        val data = ingredients().toMutableList()
        data[0] = data[0].copy(amount = "0.5", unit = "kg")
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.NORMALIZED_INGREDIENT_MISMATCH, result.category)
        assertEquals(1, result.unitMismatchCount)
    }

    @Test
    fun `normalized recipe reports quantity mismatch`() {
        val data = ingredients().toMutableList()
        data[0] = data[0].copy(amount = "450")
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.NORMALIZED_INGREDIENT_MISMATCH, result.category)
        assertEquals(1, result.quantityMismatchCount)
    }

    @Test
    fun `invalid quantity reports validator exception class without detail`() {
        val data = ingredients().toMutableList()
        data[0] = data[0].copy(amount = "0")
        val result = validator.validate(recipeJson(data))

        assertEquals(RecipeProbeFailureCategory.QUANTITY_VALIDATOR_EXCEPTION, result.category)
        assertEquals("MealPlanValidationException", result.exceptionClass)
        assertEquals(6, result.parsedIngredientCount)
        assertFalse(result.valid)
    }

    @Test
    fun `exact expected recipe remains valid`() {
        val result = validator.validate(recipeJson(ingredients()))

        assertEquals(RecipeProbeFailureCategory.VALID, result.category)
        assertEquals(6, result.parsedIngredientCount)
        assertEquals(6, result.normalizedIngredientCount)
        assertTrue(result.valid)
    }

    private fun recipeJson(items: List<FixtureIngredient>): String = JSONObject()
        .put("title", "Fixture dinner")
        .put("servings", 4)
        .put("ingredients", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().put("original_text", item.name).apply {
                    item.amount?.let { put("amount", it) }
                    item.unit?.let { put("unit", it) }
                    item.item?.let { put("item", it) }
                })
            }
        })
        .put("method_steps", JSONArray(listOf("Prepare ingredients.", "Cook thoroughly.", "Serve.")))
        .toString()

    private fun ingredients() = listOf(
        FixtureIngredient("chicken breast", "500", "g", "chicken breast"),
        FixtureIngredient("kumara", "400", "g", "kumara"),
        FixtureIngredient("carrot", "2", "whole", "carrot"),
        FixtureIngredient("red onion", "1", "whole", "red onion"),
        FixtureIngredient("olive oil", "15", "ml", "olive oil"),
        FixtureIngredient("lemon", "1", "whole", "lemon"),
    )

    private data class FixtureIngredient(
        val name: String,
        val amount: String?,
        val unit: String?,
        val item: String?,
    )
}
