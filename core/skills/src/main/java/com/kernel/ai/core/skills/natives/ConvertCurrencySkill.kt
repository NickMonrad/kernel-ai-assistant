package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import kotlinx.coroutines.CancellationException
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone currency conversion skill exposing [CurrencyConversionService] as a
 * first-class model tool. The service fetches latest ECB-backed rates via the
 * Frankfurter API and resolves natural-language currency names to ISO codes.
 */
@Singleton
class ConvertCurrencySkill @Inject constructor(
    private val currencyConversionService: CurrencyConversionService,
) : Skill {

    override val name = "convert_currency"
    override val description =
        "Convert an amount between currencies using latest ECB-backed exchange rates. " +
        "Use for any currency conversion like 'how much is X in Y' or 'convert X AUD to INR'. " +
        "Pass currency codes (AUD, USD, INR, NZD) or full names (Australian dollars, Indian rupees)."

    override val schema = SkillSchema(
        parameters = mapOf(
            "amount" to SkillParameter(
                type = "string",
                description = "The amount to convert, as a decimal string (e.g. '100', '50.75')",
            ),
            "from_currency" to SkillParameter(
                type = "string",
                description = "Source currency code (e.g. AUD, NZD, USD, EUR) or full name (Australian dollars)",
            ),
            "to_currency" to SkillParameter(
                type = "string",
                description = "Target currency code (e.g. INR, JPY, NZD) or full name (Indian rupees, Japanese yen)",
            ),
        ),
        required = listOf("amount", "from_currency", "to_currency"),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val amountRaw = call.arguments["amount"]?.trim()
            ?: return SkillResult.Failure(name, "No currency amount provided")
        val fromCurrencyRaw = call.arguments["from_currency"]?.trim()
            ?: return SkillResult.Failure(name, "No source currency provided")
        val toCurrencyRaw = call.arguments["to_currency"]?.trim()
            ?: return SkillResult.Failure(name, "No target currency provided")

        return try {
            val result = currencyConversionService.convert(
                amountRaw = amountRaw,
                fromCurrencyRaw = fromCurrencyRaw,
                toCurrencyRaw = toCurrencyRaw,
            )

            if (result.fromCurrency.code == result.toCurrency.code) {
                return SkillResult.DirectReply(
                    "${result.inputAmount.toPlainString()} ${result.fromCurrency.code} is ${result.outputAmount.toPlainString()} ${result.toCurrency.code}.",
                )
            }

            val roundedAmount = result.outputAmount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
            val roundedRate = result.rate.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
            val spokenDate = result.rateDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
            val content = "${result.inputAmount.toPlainString()} ${result.fromCurrency.code} converts to approximately ${roundedAmount.toPlainString()} ${result.toCurrency.code}. " +
                "1 ${result.fromCurrency.code} = ${roundedRate.toPlainString()} ${result.toCurrency.code}. " +
                "This uses the latest ${result.sourceLabel} from ${result.rateDate}. " +
                "Exchange rates are not real-time and may have moved since then."
            val spokenSummary = "${result.inputAmount.toPlainString()} ${result.fromCurrency.code} converts to approximately ${roundedAmount.toPlainString()} ${result.toCurrency.code} at the $spokenDate ${result.sourceLabel}."
            SkillResult.DirectReply(content, spokenSummary = spokenSummary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMessage = when (e) {
                is IllegalArgumentException -> e.message
                else -> "Currency rates are unavailable right now. I can't do a truthful conversion offline."
            }
            SkillResult.Failure(name, errorMessage ?: "Could not convert currency")
        }
    }
}
