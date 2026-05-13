package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal object UnitConversionEvaluator {
    private const val MAX_INPUT_LENGTH = 64
    private const val APPROXIMATE_SCALE = 8

    private val CELSIUS_TO_KELVIN_OFFSET = BigDecimal("273.15")
    private val FAHRENHEIT_FREEZING_OFFSET = BigDecimal("32")
    private val FAHRENHEIT_SCALE_FACTOR = BigDecimal("1.8")
    private val ABSOLUTE_ZERO_CELSIUS = BigDecimal("-273.15")
    private val ABSOLUTE_ZERO_FAHRENHEIT = BigDecimal("-459.67")

    data class Result(
        val inputValue: BigDecimal,
        val outputValue: BigDecimal,
        val fromUnit: SupportedUnit,
        val toUnit: SupportedUnit,
        val isApproximate: Boolean,
        val mixedUnitBreakdown: MixedUnitBreakdown? = null,
    )

    enum class UnitCategory {
        MASS,
        DISTANCE,
        VOLUME,
        SPEED,
        TEMPERATURE,
    }

    data class MixedUnitBreakdown(
        val primaryUnit: SupportedUnit,
        val primaryValue: BigDecimal,
        val secondaryUnit: SupportedUnit,
        val secondaryValue: BigDecimal,
    )

    enum class SupportedUnit(
        val category: UnitCategory,
        val canonicalName: String,
        val singularDisplayName: String,
        val pluralDisplayName: String,
        val baseFactor: BigDecimal? = null,
        val aliases: Set<String>,
    ) {
        MILLIGRAM(
            category = UnitCategory.MASS,
            canonicalName = "mg",
            singularDisplayName = "milligram",
            pluralDisplayName = "milligrams",
            baseFactor = BigDecimal("0.001"),
            aliases = setOf("mg", "milligram", "milligrams"),
        ),
        GRAM(
            category = UnitCategory.MASS,
            canonicalName = "g",
            singularDisplayName = "gram",
            pluralDisplayName = "grams",
            baseFactor = BigDecimal.ONE,
            aliases = setOf("g", "gram", "grams"),
        ),
        KILOGRAM(
            category = UnitCategory.MASS,
            canonicalName = "kg",
            singularDisplayName = "kilogram",
            pluralDisplayName = "kilograms",
            baseFactor = BigDecimal("1000"),
            aliases = setOf("kg", "kgs", "kilogram", "kilograms", "kilogramme", "kilogrammes"),
        ),
        OUNCE(
            category = UnitCategory.MASS,
            canonicalName = "oz",
            singularDisplayName = "ounce",
            pluralDisplayName = "ounces",
            baseFactor = BigDecimal("28.349523125"),
            aliases = setOf("oz", "ounce", "ounces"),
        ),
        POUND(
            category = UnitCategory.MASS,
            canonicalName = "lb",
            singularDisplayName = "pound",
            pluralDisplayName = "pounds",
            baseFactor = BigDecimal("453.59237"),
            aliases = setOf("lb", "lbs", "pound", "pounds"),
        ),
        MILLIMETER(
            category = UnitCategory.DISTANCE,
            canonicalName = "mm",
            singularDisplayName = "millimeter",
            pluralDisplayName = "millimeters",
            baseFactor = BigDecimal("0.001"),
            aliases = setOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"),
        ),
        CENTIMETER(
            category = UnitCategory.DISTANCE,
            canonicalName = "cm",
            singularDisplayName = "centimeter",
            pluralDisplayName = "centimeters",
            baseFactor = BigDecimal("0.01"),
            aliases = setOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"),
        ),
        METER(
            category = UnitCategory.DISTANCE,
            canonicalName = "m",
            singularDisplayName = "meter",
            pluralDisplayName = "meters",
            baseFactor = BigDecimal.ONE,
            aliases = setOf("m", "meter", "meters", "metre", "metres"),
        ),
        KILOMETER(
            category = UnitCategory.DISTANCE,
            canonicalName = "km",
            singularDisplayName = "kilometer",
            pluralDisplayName = "kilometers",
            baseFactor = BigDecimal("1000"),
            aliases = setOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"),
        ),
        INCH(
            category = UnitCategory.DISTANCE,
            canonicalName = "in",
            singularDisplayName = "inch",
            pluralDisplayName = "inches",
            baseFactor = BigDecimal("0.0254"),
            aliases = setOf("in", "inch", "inches"),
        ),
        FOOT(
            category = UnitCategory.DISTANCE,
            canonicalName = "ft",
            singularDisplayName = "foot",
            pluralDisplayName = "feet",
            baseFactor = BigDecimal("0.3048"),
            aliases = setOf("ft", "foot", "feet"),
        ),
        YARD(
            category = UnitCategory.DISTANCE,
            canonicalName = "yd",
            singularDisplayName = "yard",
            pluralDisplayName = "yards",
            baseFactor = BigDecimal("0.9144"),
            aliases = setOf("yd", "yds", "yard", "yards"),
        ),
        MILE(
            category = UnitCategory.DISTANCE,
            canonicalName = "mi",
            singularDisplayName = "mile",
            pluralDisplayName = "miles",
            baseFactor = BigDecimal("1609.344"),
            aliases = setOf("mi", "mile", "miles"),
        ),
        MILLILITER(
            category = UnitCategory.VOLUME,
            canonicalName = "mL",
            singularDisplayName = "milliliter",
            pluralDisplayName = "milliliters",
            baseFactor = BigDecimal.ONE,
            aliases = setOf("ml", "mL", "milliliter", "milliliters", "millilitre", "millilitres"),
        ),
        LITER(
            category = UnitCategory.VOLUME,
            canonicalName = "L",
            singularDisplayName = "liter",
            pluralDisplayName = "liters",
            baseFactor = BigDecimal("1000"),
            aliases = setOf("l", "L", "liter", "liters", "litre", "litres"),
        ),
        TEASPOON(
            category = UnitCategory.VOLUME,
            canonicalName = "tsp",
            singularDisplayName = "teaspoon",
            pluralDisplayName = "teaspoons",
            baseFactor = BigDecimal("4.92892159375"),
            aliases = setOf("tsp", "teaspoon", "teaspoons"),
        ),
        TABLESPOON(
            category = UnitCategory.VOLUME,
            canonicalName = "tbsp",
            singularDisplayName = "tablespoon",
            pluralDisplayName = "tablespoons",
            baseFactor = BigDecimal("14.78676478125"),
            aliases = setOf("tbsp", "tablespoon", "tablespoons"),
        ),
        FLUID_OUNCE(
            category = UnitCategory.VOLUME,
            canonicalName = "fl oz",
            singularDisplayName = "fluid ounce",
            pluralDisplayName = "fluid ounces",
            baseFactor = BigDecimal("29.5735295625"),
            aliases = setOf("fl oz", "floz", "fluid ounce", "fluid ounces"),
        ),
        CUP(
            category = UnitCategory.VOLUME,
            canonicalName = "cup",
            singularDisplayName = "cup",
            pluralDisplayName = "cups",
            baseFactor = BigDecimal("236.5882365"),
            aliases = setOf("cup", "cups"),
        ),
        PINT(
            category = UnitCategory.VOLUME,
            canonicalName = "pt",
            singularDisplayName = "pint",
            pluralDisplayName = "pints",
            baseFactor = BigDecimal("473.176473"),
            aliases = setOf("pt", "pts", "pint", "pints"),
        ),
        QUART(
            category = UnitCategory.VOLUME,
            canonicalName = "qt",
            singularDisplayName = "quart",
            pluralDisplayName = "quarts",
            baseFactor = BigDecimal("946.352946"),
            aliases = setOf("qt", "qts", "quart", "quarts"),
        ),
        GALLON(
            category = UnitCategory.VOLUME,
            canonicalName = "gal",
            singularDisplayName = "gallon",
            pluralDisplayName = "gallons",
            baseFactor = BigDecimal("3785.411784"),
            aliases = setOf("gal", "gallon", "gallons"),
        ),
        METERS_PER_SECOND(
            category = UnitCategory.SPEED,
            canonicalName = "m/s",
            singularDisplayName = "meter per second",
            pluralDisplayName = "meters per second",
            baseFactor = BigDecimal("3.6"),
            aliases = setOf(
                "m/s",
                "mps",
                "meter per second",
                "meters per second",
                "meter a second",
                "meters a second",
                "meter an second",
                "meters an second",
                "metre per second",
                "metres per second",
                "metre a second",
                "metres a second",
                "metre an second",
                "metres an second",
            ),
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
                "km a hour",
                "km an hour",
                "kilometer per hour",
                "kilometers per hour",
                "kilometer a hour",
                "kilometers a hour",
                "kilometer an hour",
                "kilometers an hour",
                "kilometre per hour",
                "kilometres per hour",
                "kilometre a hour",
                "kilometres a hour",
                "kilometre an hour",
                "kilometres an hour",
            ),
        ),
        MILES_PER_HOUR(
            category = UnitCategory.SPEED,
            canonicalName = "mph",
            singularDisplayName = "mile per hour",
            pluralDisplayName = "miles per hour",
            baseFactor = BigDecimal("1.609344"),
            aliases = setOf("mph", "mile per hour", "miles per hour"),
        ),
        CELSIUS(
            category = UnitCategory.TEMPERATURE,
            canonicalName = "celsius",
            singularDisplayName = "degree Celsius",
            pluralDisplayName = "degrees Celsius",
            aliases = setOf("c", "°c", "celsius", "degree celsius", "degrees celsius", "deg c"),
        ),
        FAHRENHEIT(
            category = UnitCategory.TEMPERATURE,
            canonicalName = "fahrenheit",
            singularDisplayName = "degree Fahrenheit",
            pluralDisplayName = "degrees Fahrenheit",
            aliases = setOf("f", "°f", "fahrenheit", "degree fahrenheit", "degrees fahrenheit", "deg f"),
        ),
        KELVIN(
            category = UnitCategory.TEMPERATURE,
            canonicalName = "kelvin",
            singularDisplayName = "kelvin",
            pluralDisplayName = "kelvins",
            aliases = setOf("k", "kelvin", "kelvins"),
        );

        fun displayName(quantity: BigDecimal): String =
            if (quantity.abs().compareTo(BigDecimal.ONE) == 0) singularDisplayName else pluralDisplayName

        companion object {
            private val aliasMap: Map<String, SupportedUnit> =
                entries.flatMap { unit -> unit.aliases.map { alias -> normalizeUnit(alias) to unit } }.toMap()

            private val routerPattern: String = entries
                .flatMap { it.aliases }
                .distinct()
                .sortedByDescending { it.length }
                .joinToString("|") { aliasToRegex(it) }

            fun from(raw: String): SupportedUnit? = aliasMap[normalizeUnit(raw)]

            fun routerRegexPattern(): String = "(?:$routerPattern)"
        }
    }

    fun supportedRouterRegexPattern(): String = SupportedUnit.routerRegexPattern()

    fun supportedMixedUnitRouterRegexPattern(): String = "(?:feet?\\s+and\\s+inches?|foot\\s+and\\s+inches?)"
    fun supportedMassRouterRegexPattern(): String = routerRegexPatternFor(UnitCategory.MASS)

    fun supportedVolumeRouterRegexPattern(): String = routerRegexPatternFor(UnitCategory.VOLUME)

    private fun routerRegexPatternFor(category: UnitCategory): String = SupportedUnit.entries
        .filter { it.category == category }
        .flatMap { it.aliases }
        .distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { aliasToRegex(it) }
        .let { pattern -> "(?:$pattern)" }

    fun convert(rawValue: String, rawFromUnit: String, rawToUnit: String): Result {
        val value = parseValue(rawValue)
        val fromUnit = SupportedUnit.from(rawFromUnit)
            ?: throw IllegalArgumentException("Unsupported unit '$rawFromUnit'")
        val toUnit = SupportedUnit.from(rawToUnit)
            ?: throw IllegalArgumentException("Unsupported unit '$rawToUnit'")
        require(fromUnit.category == toUnit.category) {
            "Cannot convert ${fromUnit.pluralDisplayName} to ${toUnit.pluralDisplayName}"
        }

        validateInputValue(value, fromUnit)

        val converted = when (fromUnit.category) {
            UnitCategory.TEMPERATURE -> convertTemperature(value, fromUnit, toUnit)
            else -> convertLinear(value, fromUnit, toUnit)
        }
        val mixedUnitBreakdown = buildMixedUnitBreakdown(converted.first, toUnit)

        return Result(
            inputValue = value.normalizeForOutput(),
            outputValue = converted.first.normalizeForOutput(),
            fromUnit = fromUnit,
            toUnit = toUnit,
            isApproximate = converted.second,
            mixedUnitBreakdown = mixedUnitBreakdown,
        )
    }

    private fun parseValue(rawValue: String): BigDecimal {
        val trimmed = rawValue.trim()
        require(trimmed.isNotBlank()) { "No conversion value provided" }
        require(trimmed.length <= MAX_INPUT_LENGTH) { "Conversion value is too long" }
        return try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid conversion value '$rawValue'")
        }
    }

    private fun validateInputValue(value: BigDecimal, fromUnit: SupportedUnit) {
        when (fromUnit) {
            SupportedUnit.CELSIUS -> require(value >= ABSOLUTE_ZERO_CELSIUS) {
                "Temperature cannot be below absolute zero"
            }
            SupportedUnit.FAHRENHEIT -> require(value >= ABSOLUTE_ZERO_FAHRENHEIT) {
                "Temperature cannot be below absolute zero"
            }
            SupportedUnit.KELVIN -> require(value >= BigDecimal.ZERO) {
                "Kelvin value must be non-negative"
            }
            else -> require(value >= BigDecimal.ZERO) {
                "Conversion value must be non-negative"
            }
        }
    }

    private fun convertLinear(
        value: BigDecimal,
        fromUnit: SupportedUnit,
        toUnit: SupportedUnit,
    ): Pair<BigDecimal, Boolean> {
        val baseValue = value.multiply(fromUnit.baseFactor!!)
        return divideWithApproximation(baseValue, toUnit.baseFactor!!)
    }

    private fun convertTemperature(
        value: BigDecimal,
        fromUnit: SupportedUnit,
        toUnit: SupportedUnit,
    ): Pair<BigDecimal, Boolean> {
        val (celsiusValue, toCelsiusApproximate) = when (fromUnit) {
            SupportedUnit.CELSIUS -> value to false
            SupportedUnit.FAHRENHEIT ->
                divideWithApproximation(value.subtract(FAHRENHEIT_FREEZING_OFFSET), FAHRENHEIT_SCALE_FACTOR)
            SupportedUnit.KELVIN -> value.subtract(CELSIUS_TO_KELVIN_OFFSET) to false
            else -> error("Temperature conversion requested for non-temperature source unit")
        }

        val (outputValue, fromCelsiusApproximate) = when (toUnit) {
            SupportedUnit.CELSIUS -> celsiusValue to false
            SupportedUnit.FAHRENHEIT ->
                celsiusValue.multiply(FAHRENHEIT_SCALE_FACTOR).add(FAHRENHEIT_FREEZING_OFFSET) to false
            SupportedUnit.KELVIN -> celsiusValue.add(CELSIUS_TO_KELVIN_OFFSET) to false
            else -> error("Temperature conversion requested for non-temperature target unit")
        }

        return outputValue to (toCelsiusApproximate || fromCelsiusApproximate)
    }

    private fun buildMixedUnitBreakdown(
        outputValue: BigDecimal,
        toUnit: SupportedUnit,
    ): MixedUnitBreakdown? {
        if (toUnit != SupportedUnit.INCH) return null

        val totalInches = outputValue.max(BigDecimal.ZERO)
        val feetValue = totalInches.divideToIntegralValue(BigDecimal("12"))
        val remainingInches = totalInches.remainder(BigDecimal("12")).normalizeForOutput()
        if (feetValue == BigDecimal.ZERO) return null

        return MixedUnitBreakdown(
            primaryUnit = SupportedUnit.FOOT,
            primaryValue = feetValue.normalizeForOutput(),
            secondaryUnit = SupportedUnit.INCH,
            secondaryValue = remainingInches,
        )
    }

    private fun divideWithApproximation(left: BigDecimal, right: BigDecimal): Pair<BigDecimal, Boolean> =
        try {
            left.divide(right) to false
        } catch (_: ArithmeticException) {
            left.divide(right, APPROXIMATE_SCALE, RoundingMode.HALF_UP) to true
        }

    private fun normalizeUnit(raw: String): String =
        raw.lowercase(Locale.ENGLISH)
            .replace(Regex("""[°º]"""), " ")
            .replace('.', ' ')
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun aliasToRegex(alias: String): String = buildString {
        alias.forEach { char ->
            when (char) {
                ' ' -> append("""(?:\s+|-)""")
                '/' -> append("""\s*/\s*""")
                '°', 'º' -> append("""[°º]?\s*""")
                else -> append(Regex.escape(char.toString()))
            }
        }
    }

    private fun BigDecimal.normalizeForOutput(): BigDecimal =
        if (compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else stripTrailingZeros()
}
