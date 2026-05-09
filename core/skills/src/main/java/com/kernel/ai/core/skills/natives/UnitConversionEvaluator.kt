package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal object UnitConversionEvaluator {
    private const val MAX_INPUT_LENGTH = 64
    private const val APPROXIMATE_SCALE = 8

    data class Result(
        val inputValue: BigDecimal,
        val outputValue: BigDecimal,
        val fromUnit: SupportedUnit,
        val toUnit: SupportedUnit,
        val isApproximate: Boolean,
    )

    enum class UnitCategory {
        WEIGHT,
        DISTANCE,
        SPEED,
    }

    enum class SupportedUnit(
        val category: UnitCategory,
        val canonicalName: String,
        val singularDisplayName: String,
        val pluralDisplayName: String,
        val baseFactor: BigDecimal,
        val aliases: Set<String>,
    ) {
        GRAM(
            category = UnitCategory.WEIGHT,
            canonicalName = "g",
            singularDisplayName = "gram",
            pluralDisplayName = "grams",
            baseFactor = BigDecimal.ONE,
            aliases = setOf("g", "gram", "grams"),
        ),
        KILOGRAM(
            category = UnitCategory.WEIGHT,
            canonicalName = "kg",
            singularDisplayName = "kilogram",
            pluralDisplayName = "kilograms",
            baseFactor = BigDecimal("1000"),
            aliases = setOf("kg", "kgs", "kilogram", "kilograms"),
        ),
        OUNCE(
            category = UnitCategory.WEIGHT,
            canonicalName = "oz",
            singularDisplayName = "ounce",
            pluralDisplayName = "ounces",
            baseFactor = BigDecimal("28.349523125"),
            aliases = setOf("oz", "ounce", "ounces"),
        ),
        POUND(
            category = UnitCategory.WEIGHT,
            canonicalName = "lb",
            singularDisplayName = "pound",
            pluralDisplayName = "pounds",
            baseFactor = BigDecimal("453.59237"),
            aliases = setOf("lb", "lbs", "pound", "pounds"),
        ),
        KILOMETER(
            category = UnitCategory.DISTANCE,
            canonicalName = "km",
            singularDisplayName = "kilometer",
            pluralDisplayName = "kilometers",
            baseFactor = BigDecimal.ONE,
            aliases = setOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"),
        ),
        MILE(
            category = UnitCategory.DISTANCE,
            canonicalName = "mi",
            singularDisplayName = "mile",
            pluralDisplayName = "miles",
            baseFactor = BigDecimal("1.609344"),
            aliases = setOf("mi", "mile", "miles"),
        ),
        KILOMETERS_PER_HOUR(
            category = UnitCategory.SPEED,
            canonicalName = "km/h",
            singularDisplayName = "kilometer per hour",
            pluralDisplayName = "kilometers per hour",
            baseFactor = BigDecimal.ONE,
            aliases = setOf(
                "km/h",
                "kmh",
                "kph",
                "kilometer per hour",
                "kilometers per hour",
                "kilometre per hour",
                "kilometres per hour",
            ),
        ),
        MILES_PER_HOUR(
            category = UnitCategory.SPEED,
            canonicalName = "mph",
            singularDisplayName = "mile per hour",
            pluralDisplayName = "miles per hour",
            baseFactor = BigDecimal("1.609344"),
            aliases = setOf("mph", "mile per hour", "miles per hour"),
        );

        fun displayName(quantity: BigDecimal): String =
            if (quantity.abs().compareTo(BigDecimal.ONE) == 0) singularDisplayName else pluralDisplayName

        companion object {
            private val aliasMap: Map<String, SupportedUnit> =
                entries.flatMap { unit -> unit.aliases.map { alias -> normalizeUnit(alias) to unit } }.toMap()

            fun from(raw: String): SupportedUnit? = aliasMap[normalizeUnit(raw)]
        }
    }

    fun convert(rawValue: String, rawFromUnit: String, rawToUnit: String): Result {
        val value = parseValue(rawValue)
        val fromUnit = SupportedUnit.from(rawFromUnit)
            ?: throw IllegalArgumentException("Unsupported unit '$rawFromUnit'")
        val toUnit = SupportedUnit.from(rawToUnit)
            ?: throw IllegalArgumentException("Unsupported unit '$rawToUnit'")
        require(fromUnit.category == toUnit.category) {
            "Cannot convert ${fromUnit.pluralDisplayName} to ${toUnit.pluralDisplayName}"
        }

        val baseValue = value.multiply(fromUnit.baseFactor)
        val converted = divideWithApproximation(baseValue, toUnit.baseFactor)
        return Result(
            inputValue = value.normalizeForOutput(),
            outputValue = converted.first.normalizeForOutput(),
            fromUnit = fromUnit,
            toUnit = toUnit,
            isApproximate = converted.second,
        )
    }

    private fun parseValue(rawValue: String): BigDecimal {
        val trimmed = rawValue.trim()
        require(trimmed.isNotBlank()) { "No conversion value provided" }
        require(trimmed.length <= MAX_INPUT_LENGTH) { "Conversion value is too long" }
        return try {
            BigDecimal(trimmed).also { value ->
                require(value >= BigDecimal.ZERO) { "Conversion value must be non-negative" }
            }
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid conversion value '$rawValue'")
        }
    }

    private fun divideWithApproximation(left: BigDecimal, right: BigDecimal): Pair<BigDecimal, Boolean> =
        try {
            left.divide(right) to false
        } catch (_: ArithmeticException) {
            left.divide(right, APPROXIMATE_SCALE, RoundingMode.HALF_UP) to true
        }

    private fun normalizeUnit(raw: String): String =
        raw.lowercase(Locale.ENGLISH)
            .replace('.', ' ')
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun BigDecimal.normalizeForOutput(): BigDecimal =
        if (compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else stripTrailingZeros()
}
