package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.memory.mealplan.CanonicalGroceryItem
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealPlanQuantityValidator @Inject constructor() {
    fun validateAndNormalize(recipe: RecipeDraft): List<CanonicalGroceryItem> =
        recipe.ingredients.map { ingredient -> normalizeIngredient(recipe.servings, ingredient) }

    private fun normalizeIngredient(servings: Int, ingredient: RecipeDraftIngredient): CanonicalGroceryItem {
        val amount = ingredient.amount?.trim()
        val unit = ingredient.unit?.trim()
        val item = ingredient.item?.trim().takeIf { !it.isNullOrBlank() }
        if (amount.isNullOrBlank() || unit.isNullOrBlank() || item == null) {
            return CanonicalGroceryItem(
                displayText = ingredient.originalText,
                originalText = ingredient.originalText,
                quantity = amount,
                unit = unit,
                ingredientName = item,
                note = ingredient.note,
                normalizationStatus = GroceryNormalizationStatus.OPAQUE,
                mergeKey = item?.lowercase(),
            )
        }

        val normalizedAmount = parseAmount(amount)
            ?: throw MealPlanValidationException("Invalid ingredient amount '$amount' in '${ingredient.originalText}'.")
        val normalizedUnit = normalizeUnit(unit)
        if (normalizedUnit in LENGTH_UNITS) {
            throw MealPlanValidationException(
                "Ingredient '${ingredient.originalText}' used an unsupported length unit '$normalizedUnit'. Use grams, ml, spoon units, cloves, or whole-item counts instead.",
            )
        }
        val gramsOrMl = toCanonicalMetric(normalizedAmount, normalizedUnit)
        val category = classifyIngredient(item)
        validateMagnitude(
            servings = servings,
            ingredient = ingredient.originalText,
            quantity = normalizedAmount,
            normalizedUnit = normalizedUnit,
            gramsOrMl = gramsOrMl,
            category = category,
        )

        val quantityText = normalizedAmount.stripTrailingZeros().toPlainString()
        val displayText = buildString {
            append(quantityText)
            append(' ')
            append(normalizedUnit)
            append(' ')
            append(item)
            ingredient.note?.takeIf { it.isNotBlank() }?.let {
                append(", ")
                append(it)
            }
        }
        return CanonicalGroceryItem(
            displayText = displayText,
            originalText = ingredient.originalText,
            quantity = quantityText,
            unit = normalizedUnit,
            ingredientName = item,
            note = ingredient.note,
            normalizationStatus = GroceryNormalizationStatus.NORMALIZED,
            mergeKey = "$normalizedUnit:${item.lowercase()}",
        )
    }

    private fun parseAmount(raw: String): BigDecimal? {
        if (raw.matches(Regex("0{2,}.*"))) return null
        return raw.replace(",", "").toBigDecimalOrNull()
    }

    private fun normalizeUnit(unit: String): String = when (unit.trim().lowercase()) {
        "gram", "grams" -> "g"
        "kilogram", "kilograms", "kilogramme", "kilogrammes" -> "kg"
        "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
        "liter", "liters", "litre", "litres" -> "l"
        "tablespoon", "tablespoons" -> "tbsp"
        "teaspoon", "teaspoons" -> "tsp"
        else -> unit.trim().lowercase()
    }

    private fun toCanonicalMetric(quantity: BigDecimal, unit: String): BigDecimal? = when (unit) {
        "g" -> quantity
        "kg" -> quantity.multiply(BigDecimal("1000"))
        "ml" -> quantity
        "l" -> quantity.multiply(BigDecimal("1000"))
        "tbsp" -> quantity.multiply(BigDecimal("15"))
        "tsp" -> quantity.multiply(BigDecimal("5"))
        else -> null
    }

    private fun validateMagnitude(
        servings: Int,
        ingredient: String,
        quantity: BigDecimal,
        normalizedUnit: String,
        gramsOrMl: BigDecimal?,
        category: IngredientCategory,
    ) {
        if (quantity <= BigDecimal.ZERO) {
            throw MealPlanValidationException("Ingredient '$ingredient' used a non-positive quantity.")
        }
        if (quantity > BigDecimal("10000")) {
            throw MealPlanValidationException("Ingredient '$ingredient' used an absurd quantity '$quantity $normalizedUnit'.")
        }
        gramsOrMl?.let { value ->
            val maxPerServing = when (category) {
                IngredientCategory.PROTEIN -> BigDecimal("250")
                IngredientCategory.VEGETABLE -> BigDecimal("250")
                IngredientCategory.GRAIN -> BigDecimal("120")
                IngredientCategory.OIL -> BigDecimal("30")
                IngredientCategory.SAUCE -> BigDecimal("500")
                IngredientCategory.SPICE -> BigDecimal("20")
                IngredientCategory.UNKNOWN -> BigDecimal("1000")
            }
            val wholeRecipeCap = maxPerServing.multiply(BigDecimal(servings.coerceAtLeast(1)))
            if (value > wholeRecipeCap) {
                throw MealPlanValidationException(
                    "Ingredient '$ingredient' exceeded the plausible cap for $servings servings (${value.stripTrailingZeros().toPlainString()} $normalizedUnit).",
                )
            }
            if (normalizedUnit == "kg" && quantity > BigDecimal.ONE) {
                throw MealPlanValidationException("Ingredient '$ingredient' used an implausibly large kilogram quantity '$quantity kg'.")
            }
            if (normalizedUnit == "l" && quantity > BigDecimal("2")) {
                throw MealPlanValidationException("Ingredient '$ingredient' used an implausibly large liquid quantity '$quantity l'.")
            }
            if ((normalizedUnit == "tbsp" || normalizedUnit == "tsp") && quantity > BigDecimal("20")) {
                throw MealPlanValidationException("Ingredient '$ingredient' used an implausibly large spoon quantity '$quantity $normalizedUnit'.")
            }
        }
    }

    private fun classifyIngredient(item: String): IngredientCategory {
        val lower = item.lowercase()
        return when {
            PROTEIN_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.PROTEIN
            VEGETABLE_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.VEGETABLE
            GRAIN_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.GRAIN
            OIL_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.OIL
            SPICE_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.SPICE
            SAUCE_KEYWORDS.any { lower.contains(it) } -> IngredientCategory.SAUCE
            else -> IngredientCategory.UNKNOWN
        }
    }

    private enum class IngredientCategory {
        PROTEIN,
        VEGETABLE,
        GRAIN,
        OIL,
        SAUCE,
        SPICE,
        UNKNOWN,
    }

    private companion object {
        val PROTEIN_KEYWORDS = listOf("chicken", "beef", "pork", "lamb", "fish", "salmon", "tuna", "tofu", "beans", "lentils", "egg")
        val VEGETABLE_KEYWORDS = listOf("carrot", "capsicum", "broccoli", "onion", "tomato", "courgette", "zucchini", "bean", "pea", "spinach", "kumara", "potato")
        val GRAIN_KEYWORDS = listOf("rice", "pasta", "noodle", "quinoa", "couscous", "oat")
        val OIL_KEYWORDS = listOf("oil", "olive oil", "vegetable oil", "sesame oil")
        val SAUCE_KEYWORDS = listOf("stock", "broth", "sauce", "soy", "milk", "cream", "water", "vinegar")
        val SPICE_KEYWORDS = listOf("salt", "pepper", "ginger", "garlic", "paprika", "cumin", "curry")
        val LENGTH_UNITS = setOf(
            "mm",
            "millimeter",
            "millimeters",
            "millimetre",
            "millimetres",
            "cm",
            "centimeter",
            "centimeters",
            "centimetre",
            "centimetres",
            "in",
            "inch",
            "inches",
        )
    }
}
