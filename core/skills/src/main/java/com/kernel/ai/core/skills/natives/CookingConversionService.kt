package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CookingConversionService @Inject constructor() {
    enum class UnitCategory {
        MASS,
        VOLUME,
    }

    data class SupportedUnit(
        val category: UnitCategory,
        val canonicalName: String,
        val contentLabel: String,
        val spokenSingularName: String,
        val spokenPluralName: String,
        val millilitersPerUnit: BigDecimal? = null,
        val gramsPerUnit: BigDecimal? = null,
        val aliases: Set<String>,
        val assumptionText: String? = null,
    ) {
        fun spokenName(amount: BigDecimal): String =
            if (amount.compareTo(BigDecimal.ONE) == 0) spokenSingularName else spokenPluralName

        fun contentName(amount: BigDecimal): String =
            if (contentLabel == "cup" && amount.compareTo(BigDecimal.ONE) != 0) "cups" else contentLabel
    }

    data class Ingredient(
        val displayName: String,
        val densityGramsPerMilliliter: BigDecimal,
        val aliases: Set<String>,
    )

    data class Result(
        val inputAmount: BigDecimal,
        val fromUnit: SupportedUnit,
        val toUnit: SupportedUnit,
        val ingredientLabel: String,
        val outputAmount: BigDecimal,
        val assumptionTexts: List<String>,
        val usedIngredientDensity: Boolean,
    )

    companion object {
        private const val MAX_INPUT_LENGTH = 64

        private val GRAM = SupportedUnit(
            category = UnitCategory.MASS,
            canonicalName = "g",
            contentLabel = "g",
            spokenSingularName = "gram",
            spokenPluralName = "grams",
            gramsPerUnit = BigDecimal.ONE,
            aliases = setOf("g", "gram", "grams"),
        )
        private val KILOGRAM = SupportedUnit(
            category = UnitCategory.MASS,
            canonicalName = "kg",
            contentLabel = "kg",
            spokenSingularName = "kilogram",
            spokenPluralName = "kilograms",
            gramsPerUnit = BigDecimal("1000"),
            aliases = setOf("kg", "kgs", "kilogram", "kilograms"),
        )
        private val MILLILITER = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "mL",
            contentLabel = "mL",
            spokenSingularName = "milliliter",
            spokenPluralName = "milliliters",
            millilitersPerUnit = BigDecimal.ONE,
            aliases = setOf("ml", "mL", "milliliter", "milliliters", "millilitre", "millilitres"),
        )
        private val LITER = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "L",
            contentLabel = "L",
            spokenSingularName = "liter",
            spokenPluralName = "liters",
            millilitersPerUnit = BigDecimal("1000"),
            aliases = setOf("l", "L", "liter", "liters", "litre", "litres"),
        )
        private val TEASPOON = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "tsp",
            contentLabel = "tsp",
            spokenSingularName = "teaspoon",
            spokenPluralName = "teaspoons",
            millilitersPerUnit = BigDecimal("5"),
            aliases = setOf("tsp", "teaspoon", "teaspoons"),
            assumptionText = "teaspoons are treated as 5 mL",
        )
        private val TABLESPOON = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "tbsp",
            contentLabel = "tbsp",
            spokenSingularName = "tablespoon",
            spokenPluralName = "tablespoons",
            millilitersPerUnit = BigDecimal("15"),
            aliases = setOf("tbsp", "tablespoon", "tablespoons"),
            assumptionText = "tablespoons are treated as 15 mL",
        )
        private val AUSTRALIAN_TABLESPOON = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "AU tbsp",
            contentLabel = "AU tbsp",
            spokenSingularName = "Australian tablespoon",
            spokenPluralName = "Australian tablespoons",
            millilitersPerUnit = BigDecimal("20"),
            aliases = setOf(
                "au tbsp",
                "au tablespoons",
                "australian tbsp",
                "australian tablespoon",
                "australian tablespoons",
                "20 ml tablespoon",
                "20 mL tablespoon",
                "20 ml tablespoons",
                "20 mL tablespoons",
            ),
            assumptionText = "Australian tablespoons are treated as 20 mL",
        )
        private val CUP = SupportedUnit(
            category = UnitCategory.VOLUME,
            canonicalName = "cup",
            contentLabel = "cup",
            spokenSingularName = "cup",
            spokenPluralName = "cups",
            millilitersPerUnit = BigDecimal("250"),
            aliases = setOf("cup", "cups"),
            assumptionText = "cups are treated as 250 mL",
        )

        private val SUPPORTED_UNITS = listOf(
            GRAM,
            KILOGRAM,
            MILLILITER,
            LITER,
            TEASPOON,
            TABLESPOON,
            AUSTRALIAN_TABLESPOON,
            CUP,
        )
        private val MASS_UNITS = SUPPORTED_UNITS.filter { it.category == UnitCategory.MASS }
        private val VOLUME_UNITS = SUPPORTED_UNITS.filter { it.category == UnitCategory.VOLUME }
        private val UNIT_LOOKUP = SUPPORTED_UNITS.flatMap { unit ->
            unit.aliases.map { alias -> normalizeLookupKey(alias) to unit }
        }.toMap()

        private val SUPPORTED_INGREDIENTS = listOf(
            Ingredient(
                displayName = "butter",
                densityGramsPerMilliliter = BigDecimal("0.9333333333"),
                aliases = setOf("butter", "salted butter", "unsalted butter"),
            ),
            Ingredient(
                displayName = "plain flour",
                densityGramsPerMilliliter = BigDecimal("0.6"),
                aliases = setOf("plain flour", "flour", "all purpose flour", "all-purpose flour", "all purpose plain flour"),
            ),
            Ingredient(
                displayName = "white sugar",
                densityGramsPerMilliliter = BigDecimal("0.88"),
                aliases = setOf("white sugar", "sugar", "granulated sugar", "caster sugar"),
            ),
            Ingredient(
                displayName = "brown sugar",
                densityGramsPerMilliliter = BigDecimal("0.84"),
                aliases = setOf("brown sugar"),
            ),
            Ingredient(
                displayName = "rice",
                densityGramsPerMilliliter = BigDecimal("0.8"),
                aliases = setOf("rice", "uncooked rice", "white rice"),
            ),
            Ingredient(
                displayName = "rolled oats",
                densityGramsPerMilliliter = BigDecimal("0.36"),
                aliases = setOf("rolled oats", "rolled oat", "oats", "oat"),
            ),
            Ingredient(
                displayName = "milk",
                densityGramsPerMilliliter = BigDecimal("1.03"),
                aliases = setOf("milk", "whole milk", "full cream milk"),
            ),
            Ingredient(
                displayName = "water",
                densityGramsPerMilliliter = BigDecimal("1.0"),
                aliases = setOf("water"),
            ),
            Ingredient(
                displayName = "olive oil",
                densityGramsPerMilliliter = BigDecimal("0.91"),
                aliases = setOf("olive oil"),
            ),
            Ingredient(
                displayName = "honey",
                densityGramsPerMilliliter = BigDecimal("1.36"),
                aliases = setOf("honey"),
            ),
            Ingredient(
                displayName = "tomato paste",
                densityGramsPerMilliliter = BigDecimal("1.08"),
                aliases = setOf("tomato paste"),
            ),
            Ingredient(
                displayName = "crushed tomatoes",
                densityGramsPerMilliliter = BigDecimal("0.98"),
                aliases = setOf("crushed tomatoes", "crushed tomato", "canned crushed tomatoes"),
            ),
            Ingredient(
                displayName = "grated cheddar cheese",
                densityGramsPerMilliliter = BigDecimal("0.5"),
                aliases = setOf("grated cheese", "grated cheddar", "cheddar", "cheddar cheese", "grated cheddar cheese"),
            ),
            Ingredient(
                displayName = "grated mozzarella",
                densityGramsPerMilliliter = BigDecimal("0.46"),
                aliases = setOf("mozzarella", "mozzarella cheese", "grated mozzarella", "grated mozzarella cheese"),
            ),
            Ingredient(
                displayName = "lentils",
                densityGramsPerMilliliter = BigDecimal("0.8"),
                aliases = setOf("lentils", "dried lentils", "red lentils", "green lentils"),
            ),
        )
        private val INGREDIENT_LOOKUP = SUPPORTED_INGREDIENTS.flatMap { ingredient ->
            ingredient.aliases.map { alias -> normalizeLookupKey(alias) to ingredient }
        }.toMap()

        fun supportedVolumeRouterRegexPattern(): String = escapedAliasPattern(VOLUME_UNITS)

        fun supportedMassRouterRegexPattern(): String = escapedAliasPattern(MASS_UNITS)

        private fun escapedAliasPattern(units: List<SupportedUnit>): String = units
            .flatMap { it.aliases }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }

        private fun normalizeLookupKey(raw: String): String {
            val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
            return ascii
                .lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9\s-]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }

    fun convert(
        amountRaw: String,
        fromUnitRaw: String,
        ingredientRaw: String,
        toUnitRaw: String = "g",
    ): Result {
        val amount = parseAmount(amountRaw)
        val fromUnit = resolveUnit(fromUnitRaw)
            ?: throw IllegalArgumentException(
                "Unsupported cooking source unit '$fromUnitRaw'. Use g, kg, mL, L, tsp, tbsp, AU tbsp, or cups.",
            )
        val toUnit = resolveUnit(toUnitRaw)
            ?: throw IllegalArgumentException(
                "Unsupported cooking target unit '$toUnitRaw'. Use g, kg, mL, L, tsp, tbsp, AU tbsp, or cups.",
            )
        val ingredientLabel = normalizeIngredientDisplay(ingredientRaw)
        val sameCategory = fromUnit.category == toUnit.category
        val ingredient = if (sameCategory) {
            null
        } else {
            resolveIngredient(ingredientRaw)
                ?: throw IllegalArgumentException(
                    "Unsupported cooking ingredient '$ingredientRaw'. I can only do built-in cooking conversions for a small set of staples such as butter, plain flour, sugar, rice, oats, milk, water, olive oil, honey, tomato paste, cheese, and lentils.",
                )
        }

        val outputAmount = when {
            sameCategory && fromUnit.category == UnitCategory.VOLUME -> {
                val milliliters = amount.multiply(fromUnit.requireMillilitersPerUnit())
                milliliters.divide(toUnit.requireMillilitersPerUnit(), 10, RoundingMode.HALF_UP)
            }
            sameCategory && fromUnit.category == UnitCategory.MASS -> {
                val grams = amount.multiply(fromUnit.requireGramsPerUnit())
                grams.divide(toUnit.requireGramsPerUnit(), 10, RoundingMode.HALF_UP)
            }
            fromUnit.category == UnitCategory.VOLUME -> {
                val milliliters = amount.multiply(fromUnit.requireMillilitersPerUnit())
                val grams = milliliters.multiply(ingredient!!.densityGramsPerMilliliter)
                grams.divide(toUnit.requireGramsPerUnit(), 10, RoundingMode.HALF_UP)
            }
            else -> {
                val grams = amount.multiply(fromUnit.requireGramsPerUnit())
                val milliliters = grams.divide(ingredient!!.densityGramsPerMilliliter, 10, RoundingMode.HALF_UP)
                milliliters.divide(toUnit.requireMillilitersPerUnit(), 10, RoundingMode.HALF_UP)
            }
        }.stripTrailingZeros()

        val assumptionTexts = listOfNotNull(fromUnit.assumptionText, toUnit.assumptionText).distinct()

        return Result(
            inputAmount = amount,
            fromUnit = fromUnit,
            toUnit = toUnit,
            ingredientLabel = ingredient?.displayName ?: ingredientLabel,
            outputAmount = outputAmount,
            assumptionTexts = assumptionTexts,
            usedIngredientDensity = !sameCategory,
        )
    }

    private fun parseAmount(raw: String): BigDecimal {
        val cleaned = raw.trim().replace(",", "")
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("No cooking conversion amount provided")
        }
        if (cleaned.length > MAX_INPUT_LENGTH) {
            throw IllegalArgumentException("Cooking conversion amount is too long")
        }
        val amount = cleaned.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Could not parse cooking conversion amount '$raw'")
        if (amount < BigDecimal.ZERO) {
            throw IllegalArgumentException("Cooking conversion amount must be non-negative")
        }
        return amount.stripTrailingZeros()
    }

    private fun resolveUnit(raw: String): SupportedUnit? =
        UNIT_LOOKUP[normalizeLookupKey(raw)]

    private fun SupportedUnit.requireMillilitersPerUnit(): BigDecimal =
        millilitersPerUnit ?: throw IllegalArgumentException("Unit ${canonicalName} is not a volume unit")

    private fun SupportedUnit.requireGramsPerUnit(): BigDecimal =
        gramsPerUnit ?: throw IllegalArgumentException("Unit ${canonicalName} is not a mass unit")

    private fun resolveIngredient(raw: String): Ingredient? =
        INGREDIENT_LOOKUP[normalizeIngredientDisplay(raw)]

    private fun normalizeIngredientDisplay(raw: String): String = normalizeLookupKey(raw)
        .removePrefix("of ")
        .removePrefix("the ")
        .removePrefix("a ")
        .removePrefix("an ")
        .trim()
}
