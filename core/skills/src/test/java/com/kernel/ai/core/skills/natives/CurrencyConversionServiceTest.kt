package com.kernel.ai.core.skills.natives

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurrencyConversionServiceTest {
    @Test
    fun `convert resolves ISO codes and returns latest rate result`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient())

        val result = service.convert(
            amountRaw = "100",
            fromCurrencyRaw = "AUD",
            toCurrencyRaw = "NZD",
            today = LocalDate.of(2026, 5, 10),
        )

        assertEquals("100", result.inputAmount.toPlainString())
        assertEquals("AUD", result.fromCurrency.code)
        assertEquals("NZD", result.toCurrency.code)
        assertEquals("121.38", result.outputAmount.toPlainString())
        assertEquals("1.2138", result.rate.toPlainString())
        assertEquals(LocalDate.of(2026, 5, 8), result.rateDate)
        assertEquals("ECB reference rate via Frankfurter", result.sourceLabel)
    }

    @Test
    fun `convert resolves full currency names and colloquial aliases`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient())

        val result = service.convert(
            amountRaw = "100",
            fromCurrencyRaw = "Australian dollars",
            toCurrencyRaw = "kiwi dollars",
            today = LocalDate.of(2026, 5, 10),
        )

        assertEquals("AUD", result.fromCurrency.code)
        assertEquals("NZD", result.toCurrency.code)
    }

    @Test
    fun `convert rejects stale latest rates`() = runTest {
        val service = CurrencyConversionService(
            fakeHttpClient(
                latestJson = """
                    {
                      "amount": 1,
                      "base": "AUD",
                      "date": "2026-05-01",
                      "rates": { "NZD": 1.2000 }
                    }
                """.trimIndent(),
            ),
        )

        val error = runCatching {
            service.convert(
                amountRaw = "10",
                fromCurrencyRaw = "AUD",
                toCurrencyRaw = "NZD",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException

        assertEquals(
            "Latest available AUD to NZD rate is from 2026-05-01, which is too stale to use.",
            error.message,
        )
    }

    @Test
    fun `convert reports unsupported currencies cleanly`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient())

        val error = runCatching {
            service.convert(
                amountRaw = "10",
                fromCurrencyRaw = "galactic credits",
                toCurrencyRaw = "NZD",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException

        assertEquals(
            "Unsupported or ambiguous source currency 'galactic credits'. Use an ISO code like USD or a full name like Australian dollars.",
            error.message,
        )
    }

    private fun fakeHttpClient(
        currenciesJson: String = """
            {
              "AUD": "Australian Dollar",
              "EUR": "Euro",
              "NZD": "New Zealand Dollar",
              "USD": "United States Dollar"
            }
        """.trimIndent(),
        latestJson: String = """
            {
              "amount": 1,
              "base": "AUD",
              "date": "2026-05-08",
              "rates": { "NZD": 1.2138 }
            }
        """.trimIndent(),
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url.toString()
                val body = when {
                    url.contains("/v1/currencies") -> currenciesJson
                    url.contains("/v1/latest") -> latestJson
                    else -> error("Unexpected URL: $url")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            },
        )
        .build()
}
