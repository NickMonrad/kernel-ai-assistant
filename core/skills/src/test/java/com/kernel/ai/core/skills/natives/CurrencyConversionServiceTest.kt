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

    @Test
    fun `convert fails closed when currencies catalog is unavailable`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient(currenciesCode = 503))

        val error = runCatching {
            service.convert(
                amountRaw = "10",
                fromCurrencyRaw = "AUD",
                toCurrencyRaw = "NZD",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException

        assertEquals(
            "Currency rates are unavailable right now. I can't do a truthful conversion offline.",
            error.message,
        )
    }

    @Test
    fun `convert fails closed when latest rate lookup is unavailable`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient(latestCode = 503))

        val error = runCatching {
            service.convert(
                amountRaw = "10",
                fromCurrencyRaw = "AUD",
                toCurrencyRaw = "NZD",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException

        assertEquals(
            "Currency conversion failed: upstream unavailable",
            error.message,
        )
    }

    @Test
    fun `convert accepts latest rates up to five days old and rejects six day old rates`() = runTest {
        val successService = CurrencyConversionService(
            fakeHttpClient(
                latestJson = """
                    {
                      "amount": 1,
                      "base": "AUD",
                      "date": "2026-05-05",
                      "rates": { "NZD": 1.2000 }
                    }
                """.trimIndent(),
            ),
        )
        val success = successService.convert(
            amountRaw = "10",
            fromCurrencyRaw = "AUD",
            toCurrencyRaw = "NZD",
            today = LocalDate.of(2026, 5, 10),
        )
        assertEquals("12", success.outputAmount.toPlainString())

        val staleService = CurrencyConversionService(
            fakeHttpClient(
                latestJson = """
                    {
                      "amount": 1,
                      "base": "AUD",
                      "date": "2026-05-04",
                      "rates": { "NZD": 1.2000 }
                    }
                """.trimIndent(),
            ),
        )
        val stale = runCatching {
            staleService.convert(
                amountRaw = "10",
                fromCurrencyRaw = "AUD",
                toCurrencyRaw = "NZD",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException
        assertEquals(
            "Latest available AUD to NZD rate is from 2026-05-04, which is too stale to use.",
            stale.message,
        )
    }

    @Test
    fun `convert short circuits same currency codes after validation`() = runTest {
        var requestCount = 0
        val service = CurrencyConversionService(
            fakeHttpClient(
                onRequest = { requestCount += 1 },
            ),
        )

        val result = service.convert(
            amountRaw = "100",
            fromCurrencyRaw = "USD",
            toCurrencyRaw = "USD",
            today = LocalDate.of(2026, 5, 10),
        )

        assertEquals("100", result.outputAmount.toPlainString())
        assertEquals("1", result.rate.toPlainString())
        assertEquals(1, requestCount)
    }

    @Test
    fun `convert rejects non positive exchange rates`() = runTest {
        val service = CurrencyConversionService(
            fakeHttpClient(
                latestJson = """
                    {
                      "amount": 1,
                      "base": "AUD",
                      "date": "2026-05-08",
                      "rates": { "NZD": 0.0 }
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
            "Could not find a AUD to NZD exchange rate.",
            error.message,
        )
    }

    @Test
    fun `convert rejects unknown same pair ISO codes`() = runTest {
        val service = CurrencyConversionService(fakeHttpClient())

        val error = runCatching {
            service.convert(
                amountRaw = "100",
                fromCurrencyRaw = "XXX",
                toCurrencyRaw = "XXX",
                today = LocalDate.of(2026, 5, 10),
            )
        }.exceptionOrNull() as IllegalArgumentException

        assertEquals(
            "Unsupported or ambiguous source currency 'XXX'. Use an ISO code like USD or a full name like Australian dollars.",
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
        currenciesCode: Int = 200,
        latestCode: Int = 200,
        onRequest: (String) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url.toString()
                onRequest(url)
                val (code, body) = when {
                    url.contains("/v1/currencies") -> currenciesCode to currenciesJson
                    url.contains("/v1/latest") -> latestCode to latestJson
                    else -> error("Unexpected URL: $url")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "ERROR")
                    .body(
                        (
                            if (code == 200) body else "{\"message\":\"upstream unavailable\"}"
                        ).toResponseBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()
            },
        )
        .build()
}
