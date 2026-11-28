package com.kernel.ai.core.skills.natives

// Spoken replies for failures in the unit, arithmetic, cooking and currency intents (#2176).
// Each function maps the technical message from its evaluator or service to the sentence the user hears.
// The caller keeps the raw message as technicalDetail. Missing or unparseable input gets the intent's
// generic reply, and so does any message not listed here. Currency falls back to the rates-unavailable reply.
internal object FriendlyFailureReplies {
    const val UNIT_CONVERSION_GENERIC = "Sorry, I couldn't work out that conversion. Try 'convert 3/4 cup to ml'."
    const val ARITHMETIC_GENERIC = "Sorry, I couldn't work out that calculation."
    const val COOKING_GENERIC = "Sorry, I couldn't work out that cooking conversion. Include the amount, unit and ingredient."
    const val CURRENCY_GENERIC = "Sorry, I couldn't work out that currency conversion. Include the amount and both currencies."
    const val CURRENCY_UNAVAILABLE = "Currency rates are unavailable right now. I can't do a truthful conversion offline."

    private val MISSING_CURRENCY_INPUT = setOf(
        "No currency amount provided",
        "No source currency provided",
        "No target currency provided",
    )

    private val unsupportedUnit = Regex("""Unsupported unit '(.+)'""")
    private val differentKinds = Regex("""Cannot convert (.+) to (.+)""")

    private val unexpectedNearEnd = Regex("""Unexpected token near '(.+)'""")
    private val unexpectedToken = Regex("""Unexpected token '(.+)'""")
    private val missingClosingParen = Regex("""Missing closing '\)'.*""")
    private val invalidNumber = Regex("""Invalid number '(.+)'""")
    private val expectedOpenParen = Regex("""Expected '\(' after (\w+)""")
    private val singleArgumentFunction = Regex("""(\w+)\(\) takes exactly 1 argument""")
    private val roundScale = Regex("""round\(\) scale must .*""")
    private val unsupportedFunction = Regex("""Unsupported function '(.+)'""")

    private val unsupportedCookingUnit = Regex("""Unsupported cooking (?:source|target) unit '(.+?)'\. (Use .+)""")
    private val unsupportedCookingIngredient = Regex("""Unsupported cooking ingredient '(.+?)'\.\s*(.*)""")

    private val unsupportedCurrency = Regex("""Unsupported or ambiguous (source|target) currency '(.+?)'\. (.+)""")
    private val staleRate = Regex("""Latest available (\w+) to (\w+) rate is from (.+), which is too stale to use\.""")

    fun unitConversion(message: String): String {
        unsupportedUnit.captures(message)?.let { return "I don't know the unit '${it[0]}'." }
        differentKinds.captures(message)?.let {
            return "I can't convert ${it[0]} to ${it[1]}; they measure different things."
        }
        return when (message) {
            "Conversion value is too long" -> "That number is too long for me to convert."
            "Temperature cannot be below absolute zero" ->
                "That temperature is below absolute zero, the lowest possible temperature."
            "Kelvin value must be non-negative" -> "Kelvin can't be negative. Absolute zero is 0 K."
            "Conversion value must be non-negative" -> "I can't convert a negative value."
            else -> UNIT_CONVERSION_GENERIC
        }
    }

    fun arithmetic(message: String): String {
        unexpectedNearEnd.captures(message)?.let { return "I couldn't follow that calculation near '${it[0]}'." }
        unexpectedToken.captures(message)?.let { return "I didn't expect '${it[0]}' in that calculation." }
        invalidNumber.captures(message)?.let { return "'${it[0]}' isn't a number I can use." }
        expectedOpenParen.captures(message)?.let {
            return "${it[0]} needs brackets around its value, like ${it[0]}(4)."
        }
        singleArgumentFunction.captures(message)?.let { return "${it[0]}() takes exactly one value." }
        unsupportedFunction.captures(message)?.let { return "I don't know a function called '${it[0]}'." }
        if (missingClosingParen.matches(message)) return "A bracket is missing its closing ')'."
        if (roundScale.matches(message)) return "round() needs a whole number of decimal places."
        return when (message) {
            "Expression is too long" -> "That expression is too long for me to work out."
            "Unexpected end of expression" -> "That calculation stops before it's finished."
            "Division by zero is undefined" -> "I can't divide by zero."
            "Modulo by zero is undefined" -> "I can't work out a remainder when dividing by zero."
            "sqrt() is undefined for negative numbers" -> "I can't take the square root of a negative number."
            "Exponent must be a whole number" -> "I can only raise numbers to whole-number powers."
            "Exponent is too large" -> "That power is too large for me to work out."
            "round() takes 1 or 2 arguments" -> "round() takes one or two values."
            else -> ARITHMETIC_GENERIC
        }
    }

    fun cookingConversion(message: String): String {
        unsupportedCookingUnit.captures(message)?.let { return "I don't know the cooking unit '${it[0]}'. ${it[1]}" }
        unsupportedCookingIngredient.captures(message)?.let { (name, rest) ->
            return listOf("I don't have a built-in cooking conversion for '$name'.", rest)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        return when (message) {
            "Cooking conversion amount is too long" -> "That amount is too long for me to convert."
            "Cooking conversion amount must be non-negative" -> "I can't convert a negative amount."
            else -> COOKING_GENERIC
        }
    }

    fun currencyConversion(message: String): String {
        unsupportedCurrency.captures(message)?.let { (kind, code, hint) ->
            return "I can't tell which $kind currency '$code' is. $hint"
        }
        staleRate.captures(message)?.let { (from, to, date) ->
            return "The latest $from to $to rate I can get is from $date, which is too old to use."
        }
        return when {
            message == "Currency amount is too long" -> "That amount is too long for me to convert."
            message == "Currency amount must be non-negative" -> "I can't convert a negative amount."
            message in MISSING_CURRENCY_INPUT || message.startsWith("Could not parse currency amount") -> CURRENCY_GENERIC
            else -> CURRENCY_UNAVAILABLE
        }
    }

    private fun Regex.captures(message: String): List<String>? = matchEntire(message)?.groupValues?.drop(1)
}
