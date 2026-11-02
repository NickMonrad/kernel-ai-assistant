package com.kernel.ai.debug.llamaprobe

import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator

public enum class RecipeProbeFailureCategory(val wireName: String) {
    PARSER_EXCEPTION("parser_exception"),
    QUANTITY_VALIDATOR_EXCEPTION("quantity_validator_exception"),
    INGREDIENT_COUNT_MISMATCH("ingredient_count_mismatch"),
    PARSED_UNNORMALIZED_INGREDIENTS("parsed_unnormalized_ingredients"),
    NORMALIZED_INGREDIENT_MISMATCH("normalized_ingredient_mismatch"),
    VALID("valid"),
}

/** Sanitized, probe-only outcome. Deliberately contains no generated recipe text or ingredient names. */
public data class RecipeProbeValidation(
    val category: RecipeProbeFailureCategory,
    val parsedIngredientCount: Int?,
    val normalizedIngredientCount: Int,
    val nameMismatchCount: Int,
    val unitMismatchCount: Int,
    val quantityMismatchCount: Int,
    val exceptionClass: String?,
    val validationMs: Long,
) {
    val valid: Boolean get() = category == RecipeProbeFailureCategory.VALID
}

/** Runs the existing application parser and quantity validator without weakening their acceptance rules. */
public class RecipeProbeValidator(
    private val parser: MealPlanJsonParser,
    private val quantityValidator: MealPlanQuantityValidator,
) {
    fun validate(text: String): RecipeProbeValidation {
        val started = System.nanoTime()
        fun result(
            category: RecipeProbeFailureCategory,
            parsedCount: Int? = null,
            normalizedCount: Int = 0,
            nameMismatches: Int = 0,
            unitMismatches: Int = 0,
            quantityMismatches: Int = 0,
            exceptionClass: String? = null,
        ) = RecipeProbeValidation(
            category = category,
            parsedIngredientCount = parsedCount,
            normalizedIngredientCount = normalizedCount,
            nameMismatchCount = nameMismatches,
            unitMismatchCount = unitMismatches,
            quantityMismatchCount = quantityMismatches,
            exceptionClass = exceptionClass,
            validationMs = (System.nanoTime() - started) / 1_000_000,
        )

        val recipe = try {
            parser.parseRecipeDraft(text, expectedServings = 4)
        } catch (failure: Exception) {
            return result(
                category = RecipeProbeFailureCategory.PARSER_EXCEPTION,
                exceptionClass = failure.javaClass.simpleName.ifBlank { "UnknownException" },
            )
        }

        val items = try {
            quantityValidator.validateAndNormalize(recipe)
        } catch (failure: Exception) {
            return result(
                category = RecipeProbeFailureCategory.QUANTITY_VALIDATOR_EXCEPTION,
                parsedCount = recipe.ingredients.size,
                exceptionClass = failure.javaClass.simpleName.ifBlank { "UnknownException" },
            )
        }

        val normalizedCount = items.count { it.normalizationStatus == GroceryNormalizationStatus.NORMALIZED }
        val expectedMatches = EXPECTED_INGREDIENTS.map { expected ->
            items.firstOrNull { it.ingredientName?.lowercase()?.contains(expected.name) == true }
        }
        val nameMismatches = expectedMatches.count { it == null }
        val unitMismatches = EXPECTED_INGREDIENTS.indices.count { index ->
            expectedMatches[index]?.let { it.unit != EXPECTED_INGREDIENTS[index].unit } == true
        }
        val quantityMismatches = EXPECTED_INGREDIENTS.indices.count { index ->
            expectedMatches[index]?.let { it.quantity != EXPECTED_INGREDIENTS[index].quantity } == true
        }
        val category = when {
            recipe.ingredients.size != EXPECTED_INGREDIENTS.size || items.size != EXPECTED_INGREDIENTS.size ->
                RecipeProbeFailureCategory.INGREDIENT_COUNT_MISMATCH
            normalizedCount != EXPECTED_INGREDIENTS.size ->
                RecipeProbeFailureCategory.PARSED_UNNORMALIZED_INGREDIENTS
            nameMismatches + unitMismatches + quantityMismatches > 0 ->
                RecipeProbeFailureCategory.NORMALIZED_INGREDIENT_MISMATCH
            else -> RecipeProbeFailureCategory.VALID
        }
        return result(
            category = category,
            parsedCount = recipe.ingredients.size,
            normalizedCount = normalizedCount,
            nameMismatches = nameMismatches,
            unitMismatches = unitMismatches,
            quantityMismatches = quantityMismatches,
        )
    }

    private data class ExpectedIngredient(val name: String, val quantity: String, val unit: String)

    private companion object {
        val EXPECTED_INGREDIENTS = listOf(
            ExpectedIngredient("chicken breast", "500", "g"),
            ExpectedIngredient("kumara", "400", "g"),
            ExpectedIngredient("carrot", "2", "whole"),
            ExpectedIngredient("red onion", "1", "whole"),
            ExpectedIngredient("olive oil", "15", "ml"),
            ExpectedIngredient("lemon", "1", "whole"),
        )
    }
}
