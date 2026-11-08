package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

internal object ArithmeticExpressionEvaluator {
    private const val MAX_INPUT_LENGTH = 256
    private const val APPROXIMATE_SCALE = 16
    private const val MAX_ROUND_SCALE = 16
    private const val MAX_EXPONENT = 1000
    private val SQRT_CONTEXT = MathContext.DECIMAL128

    data class Result(
        val value: BigDecimal,
        val isApproximate: Boolean,
    )

    fun evaluate(rawInput: String): Result {
        val normalized = normalizeInput(rawInput)
        require(normalized.isNotBlank()) { "No expression provided" }
        require(normalized.length <= MAX_INPUT_LENGTH) { "Expression is too long" }

        val parser = Parser(normalized)
        val value = parser.parseExpression()
        parser.skipWhitespace()
        require(parser.isAtEnd()) { "Unexpected token near '${parser.remainingInput()}'" }

        return Result(value.number.normalizeForOutput(), isApproximate = !value.exact)
    }

    private fun normalizeInput(rawInput: String): String {
        var normalized = rawInput.trim()
            .replace(Regex("""[?!.]+$"""), "")
            .replace('×', '*')
            .replace('÷', '/')
            .trim()

        normalized = normalized.replace(
            Regex("""^(?:what(?:'s|\s+is)|calculate|compute|evaluate)\s+""", RegexOption.IGNORE_CASE),
            "",
        )

        normalized = Regex(
            """(-?\d+(?:\.\d+)?)\s*(?:%|percent)\s+of\s+(-?\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        ).replace(normalized) { match ->
            "(${match.groupValues[1]} / 100) * ${match.groupValues[2]}"
        }

        normalized = normalized
            .replace(Regex("""\bto\s+the\s+power\s+of\b""", RegexOption.IGNORE_CASE), "^")
            .replace(Regex("""\braised\s+to\b""", RegexOption.IGNORE_CASE), "^")
            .replace(Regex("""\bmultiplied\s+by\b""", RegexOption.IGNORE_CASE), "*")
            .replace(Regex("""\bdivided\s+by\b""", RegexOption.IGNORE_CASE), "/")
            .replace(Regex("""\bmodulo\b|\bmod\b""", RegexOption.IGNORE_CASE), "%")
            .replace(Regex("""\bplus\b""", RegexOption.IGNORE_CASE), "+")
            .replace(Regex("""\bminus\b""", RegexOption.IGNORE_CASE), "-")
            .replace(Regex("""\btimes\b""", RegexOption.IGNORE_CASE), "*")
            .replace(Regex("""\bover\b""", RegexOption.IGNORE_CASE), "/")

        normalized = Regex("""(-?\d+(?:\.\d+)?)%(?!\s*\d)""").replace(normalized) { match ->
            "(${match.groupValues[1]} / 100)"
        }

        return normalized.replace(Regex("""\s+"""), " ").trim()
    }

    private data class EvaluatedNumber(
        val number: BigDecimal,
        val exact: Boolean,
    )

    private class Parser(private val input: String) {
        private var index: Int = 0

        fun parseExpression(): EvaluatedNumber = parseAdditive()

        fun skipWhitespace() {
            while (!isAtEnd() && input[index].isWhitespace()) index += 1
        }

        fun isAtEnd(): Boolean = index >= input.length

        fun remainingInput(): String = input.substring(index).take(16)

        private fun parseAdditive(): EvaluatedNumber {
            var value = parseMultiplicative()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('+') -> combine(value, parseMultiplicative()) { left, right ->
                        EvaluatedNumber(left.number.add(right.number), left.exact && right.exact)
                    }
                    consume('-') -> combine(value, parseMultiplicative()) { left, right ->
                        EvaluatedNumber(left.number.subtract(right.number), left.exact && right.exact)
                    }
                    else -> return value
                }
            }
        }

        private fun parseMultiplicative(): EvaluatedNumber {
            var value = parsePower()
            while (true) {
                skipWhitespace()
                value = when {
                    consume('*') -> combine(value, parsePower()) { left, right ->
                        EvaluatedNumber(left.number.multiply(right.number), left.exact && right.exact)
                    }
                    consume('/') -> divide(value, parsePower())
                    consume('%') -> remainder(value, parsePower())
                    else -> return value
                }
            }
        }

        private fun parsePower(): EvaluatedNumber {
            var value = parseUnary()
            skipWhitespace()
            if (consume('^')) {
                value = power(value, parsePower())
            }
            return value
        }

        private fun parseUnary(): EvaluatedNumber {
            skipWhitespace()
            return when {
                consume('+') -> parseUnary()
                consume('-') -> {
                    val nested = parseUnary()
                    EvaluatedNumber(nested.number.negate(), nested.exact)
                }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): EvaluatedNumber {
            skipWhitespace()
            require(!isAtEnd()) { "Unexpected end of expression" }

            return when {
                consume('(') -> {
                    val value = parseExpression()
                    skipWhitespace()
                    require(consume(')')) { "Missing closing ')'" }
                    value
                }
                currentChar().isDigit() || currentChar() == '.' -> parseNumber()
                currentChar().isLetter() -> parseFunctionCall()
                else -> throw IllegalArgumentException("Unexpected token '${currentChar()}'")
            }
        }

        private fun parseNumber(): EvaluatedNumber {
            val start = index
            var seenDot = false
            while (!isAtEnd()) {
                val c = input[index]
                when {
                    c.isDigit() -> index += 1
                    c == '.' && !seenDot -> {
                        seenDot = true
                        index += 1
                    }
                    else -> break
                }
            }
            val token = input.substring(start, index)
            require(token.any(Char::isDigit)) { "Invalid number '$token'" }
            return EvaluatedNumber(BigDecimal(token), exact = true)
        }

        private fun parseFunctionCall(): EvaluatedNumber {
            val name = parseIdentifier().lowercase(Locale.ENGLISH)
            skipWhitespace()
            require(consume('(')) { "Expected '(' after $name" }

            val arguments = mutableListOf<EvaluatedNumber>()
            skipWhitespace()
            if (!consume(')')) {
                do {
                    arguments += parseExpression()
                    skipWhitespace()
                } while (consume(','))
                require(consume(')')) { "Missing closing ')' after $name arguments" }
            }

            return applyFunction(name, arguments)
        }

        private fun applyFunction(name: String, arguments: List<EvaluatedNumber>): EvaluatedNumber = when (name) {
            "abs" -> {
                require(arguments.size == 1) { "abs() takes exactly 1 argument" }
                EvaluatedNumber(arguments[0].number.abs(), arguments[0].exact)
            }
            "floor" -> {
                require(arguments.size == 1) { "floor() takes exactly 1 argument" }
                EvaluatedNumber(arguments[0].number.setScale(0, RoundingMode.FLOOR), arguments[0].exact)
            }
            "ceil" -> {
                require(arguments.size == 1) { "ceil() takes exactly 1 argument" }
                EvaluatedNumber(arguments[0].number.setScale(0, RoundingMode.CEILING), arguments[0].exact)
            }
            "round" -> round(arguments)
            "sqrt" -> sqrt(arguments)
            else -> throw IllegalArgumentException("Unsupported function '$name'")
        }

        private fun round(arguments: List<EvaluatedNumber>): EvaluatedNumber {
            require(arguments.size in 1..2) { "round() takes 1 or 2 arguments" }
            val scale = if (arguments.size == 2) {
                try {
                    arguments[1].number.intValueExact()
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("round() scale must be a whole number")
                }
            } else {
                0
            }
            require(scale in 0..MAX_ROUND_SCALE) { "round() scale must be between 0 and $MAX_ROUND_SCALE" }
            return EvaluatedNumber(arguments[0].number.setScale(scale, RoundingMode.HALF_UP), arguments.all { it.exact })
        }

        private fun sqrt(arguments: List<EvaluatedNumber>): EvaluatedNumber {
            require(arguments.size == 1) { "sqrt() takes exactly 1 argument" }
            val value = arguments[0].number
            require(value.signum() >= 0) { "sqrt() is undefined for negative numbers" }
            val result = value.sqrt(SQRT_CONTEXT)
            val exact = arguments[0].exact && result.multiply(result).compareTo(value) == 0
            return EvaluatedNumber(result, exact)
        }

        private fun power(left: EvaluatedNumber, right: EvaluatedNumber): EvaluatedNumber {
            val exponent = try {
                right.number.intValueExact()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Exponent must be a whole number")
            }
            require(kotlin.math.abs(exponent) <= MAX_EXPONENT) { "Exponent is too large" }

            return if (exponent >= 0) {
                EvaluatedNumber(left.number.pow(exponent), left.exact && right.exact)
            } else {
                divide(
                    EvaluatedNumber(BigDecimal.ONE, exact = true),
                    EvaluatedNumber(left.number.pow(-exponent), exact = left.exact && right.exact),
                )
            }
        }

        private fun divide(left: EvaluatedNumber, right: EvaluatedNumber): EvaluatedNumber {
            require(right.number.compareTo(BigDecimal.ZERO) != 0) { "Division by zero is undefined" }
            return try {
                EvaluatedNumber(left.number.divide(right.number), left.exact && right.exact)
            } catch (_: ArithmeticException) {
                EvaluatedNumber(
                    left.number.divide(right.number, APPROXIMATE_SCALE, RoundingMode.HALF_UP),
                    exact = false,
                )
            }
        }

        private fun remainder(left: EvaluatedNumber, right: EvaluatedNumber): EvaluatedNumber {
            require(right.number.compareTo(BigDecimal.ZERO) != 0) { "Modulo by zero is undefined" }
            return EvaluatedNumber(left.number.remainder(right.number), left.exact && right.exact)
        }

        private fun parseIdentifier(): String {
            val start = index
            while (!isAtEnd() && input[index].isLetter()) index += 1
            return input.substring(start, index)
        }

        private fun currentChar(): Char = input[index]

        private fun consume(expected: Char): Boolean {
            skipWhitespace()
            if (!isAtEnd() && input[index] == expected) {
                index += 1
                return true
            }
            return false
        }

        private inline fun combine(
            left: EvaluatedNumber,
            right: EvaluatedNumber,
            op: (EvaluatedNumber, EvaluatedNumber) -> EvaluatedNumber,
        ): EvaluatedNumber = op(left, right)
    }

    private fun BigDecimal.normalizeForOutput(): BigDecimal =
        if (compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else stripTrailingZeros()
}
