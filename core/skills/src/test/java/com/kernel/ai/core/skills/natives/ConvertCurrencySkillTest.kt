package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConvertCurrencySkillTest {

    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val skill = ConvertCurrencySkill(currencyConversionService)

    private val unavailableText =
        "Currency rates are unavailable right now. I can't do a truthful conversion offline."

    private fun call(
        amount: String? = "100",
        from: String? = "AUD",
        to: String? = "NZD",
    ): SkillCall = SkillCall(
        skillName = "convert_currency",
        arguments = buildMap {
            amount?.let { put("amount", it) }
            from?.let { put("from_currency", it) }
            to?.let { put("to_currency", it) }
        },
    )

    @Test
    fun `model route maps a missing rate to the specific reply and keeps the raw text`() = runTest {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws IllegalArgumentException("Could not find a AUD to NZD exchange rate.")

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                "I couldn't get a AUD to NZD rate right now.",
                technicalDetail = "Could not find a AUD to NZD exchange rate.",
            ),
            skill.execute(call()),
        )
    }

    @Test
    fun `model route maps an HTTP error from the rate service to the specific reply`() = runTest {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws IllegalArgumentException("Currency conversion failed for AUD to NZD: HTTP 404")

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                "I couldn't get a AUD to NZD rate right now.",
                technicalDetail = "Currency conversion failed for AUD to NZD: HTTP 404",
            ),
            skill.execute(call()),
        )
    }

    @Test
    fun `model route says rates are unavailable when the service is down`() = runTest {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws IllegalArgumentException(unavailableText)

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                unavailableText,
                technicalDetail = unavailableText,
            ),
            skill.execute(call()),
        )
    }

    @Test
    fun `model route says rates are unavailable for network failures`() = runTest {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws RuntimeException("socket timeout")

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                FriendlyFailureReplies.CURRENCY_UNAVAILABLE,
                technicalDetail = "socket timeout",
            ),
            skill.execute(call()),
        )
    }

    @Test
    fun `missing amount gets the generic reply with the raw text in technicalDetail`() = runTest {
        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                FriendlyFailureReplies.CURRENCY_GENERIC,
                technicalDetail = "No currency amount provided",
            ),
            skill.execute(call(amount = null)),
        )
    }
}
