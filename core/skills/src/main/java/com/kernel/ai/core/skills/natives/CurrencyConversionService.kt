package com.kernel.ai.core.skills.natives

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class CurrencyConversionService @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    data class ResolvedCurrency(
        val code: String,
        val displayName: String,
    )

    data class Result(
        val inputAmount: BigDecimal,
        val fromCurrency: ResolvedCurrency,
        val toCurrency: ResolvedCurrency,
        val outputAmount: BigDecimal,
        val rate: BigDecimal,
        val rateDate: LocalDate,
        val sourceLabel: String,
    )

    companion object {
        private const val MAX_INPUT_LENGTH = 64
        private const val MAX_RATE_AGE_DAYS = 5L
        private const val CURRENCIES_URL = "https://api.frankfurter.dev/v1/currencies"
        private const val RATE_URL_PREFIX = "https://api.frankfurter.dev/v1/latest"

        private val COLLOQUIAL_ALIASES = mapOf(
            "aussie dollar" to "AUD",
            "aussie dollars" to "AUD",
            "kiwi dollar" to "NZD",
            "kiwi dollars" to "NZD",
            "us dollar" to "USD",
            "us dollars" to "USD",
            "american dollar" to "USD",
            "american dollars" to "USD",
            "british pound" to "GBP",
            "british pounds" to "GBP",
            "pound sterling" to "GBP",
            "sterling" to "GBP",
            "quid" to "GBP",
        )
    }

    private val catalogMutex = Mutex()
    @Volatile private var cachedCurrencies: Map<String, ResolvedCurrency>? = null

    suspend fun convert(
        amountRaw: String,
        fromCurrencyRaw: String,
        toCurrencyRaw: String,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Result {
        val amount = parseAmount(amountRaw)
        val normalizedFromCode = fromCurrencyRaw.trim().uppercase(Locale.US)
        val normalizedToCode = toCurrencyRaw.trim().uppercase(Locale.US)
        if (normalizedFromCode.matches(Regex("""[A-Z]{3}""")) && normalizedFromCode == normalizedToCode) {
            return Result(
                inputAmount = amount,
                fromCurrency = ResolvedCurrency(normalizedFromCode, normalizedFromCode),
                toCurrency = ResolvedCurrency(normalizedToCode, normalizedToCode),
                outputAmount = amount,
                rate = BigDecimal.ONE,
                rateDate = today,
                sourceLabel = "identity conversion",
            )
        }

        val catalog = loadSupportedCurrencies()
        val fromCurrency = resolveCurrency(fromCurrencyRaw, catalog)
            ?: throw IllegalArgumentException(
                "Unsupported or ambiguous source currency '$fromCurrencyRaw'. Use an ISO code like USD or a full name like Australian dollars.",
            )
        val toCurrency = resolveCurrency(toCurrencyRaw, catalog)
            ?: throw IllegalArgumentException(
                "Unsupported or ambiguous target currency '$toCurrencyRaw'. Use an ISO code like NZD or a full name like New Zealand dollars.",
            )

        if (fromCurrency.code == toCurrency.code) {
            return Result(
                inputAmount = amount,
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                outputAmount = amount,
                rate = BigDecimal.ONE,
                rateDate = today,
                sourceLabel = "identity conversion",
            )
        }

        return fetchLatestRate(
            amount = amount,
            fromCurrency = fromCurrency,
            toCurrency = toCurrency,
            today = today,
        )
    }

    private fun parseAmount(raw: String): BigDecimal {
        val cleaned = raw.trim().replace(",", "")
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("No currency amount provided")
        }
        if (cleaned.length > MAX_INPUT_LENGTH) {
            throw IllegalArgumentException("Currency amount is too long")
        }
        val amount = cleaned.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Could not parse currency amount '$raw'")
        if (amount < BigDecimal.ZERO) {
            throw IllegalArgumentException("Currency amount must be non-negative")
        }
        return amount.stripTrailingZeros()
    }

    private suspend fun loadSupportedCurrencies(): Map<String, ResolvedCurrency> {
        cachedCurrencies?.let { return it }
        return catalogMutex.withLock {
            cachedCurrencies?.let { return@withLock it }
            val request = Request.Builder()
                .url(CURRENCIES_URL)
                .header("User-Agent", "KernelAI/1.0 (Android)")
                .build()
            val catalog = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalArgumentException(
                            "Currency rates are unavailable right now. I can't do a truthful conversion offline.",
                        )
                    }
                    val body = response.body?.string()
                        ?: throw IllegalArgumentException(
                            "Currency rates are unavailable right now. I can't do a truthful conversion offline.",
                        )
                    val json = JSONObject(body)
                    buildMap<String, ResolvedCurrency> {
                        json.keys().forEach { code ->
                            val name = json.optString(code).takeIf { it.isNotBlank() } ?: return@forEach
                            put(code.uppercase(Locale.US), ResolvedCurrency(code.uppercase(Locale.US), name))
                        }
                    }
                }
            }
            cachedCurrencies = catalog
            catalog
        }
    }

    private fun resolveCurrency(
        raw: String,
        catalog: Map<String, ResolvedCurrency>,
    ): ResolvedCurrency? {
        val trimmed = raw.trim().trim('.', ',', '?', '!')
        if (trimmed.isBlank()) return null

        val upper = trimmed.uppercase(Locale.US)
        if (upper.matches(Regex("""[A-Z]{3}"""))) {
            return catalog[upper]
        }

        COLLOQUIAL_ALIASES[normalizeLookupKey(trimmed)]?.let { code ->
            return catalog[code]
        }

        val normalizedTarget = normalizeLookupKey(trimmed)
        return catalog.values.firstOrNull { currency ->
            normalizeLookupKey(currency.displayName) == normalizedTarget
        }
    }

    private fun normalizeLookupKey(raw: String): String {
        val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
        val collapsed = ascii
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val words = collapsed.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.isEmpty()) return collapsed
        val last = words.last()
        words[words.lastIndex] = singularize(last)
        return words.joinToString(" ")
    }

    private fun singularize(word: String): String = when {
        word.endsWith("ies") && word.length > 3 -> word.dropLast(3) + "y"
        word.endsWith("ses") && word.length > 3 -> word.dropLast(2)
        word.endsWith("s") && !word.endsWith("ss") && word !in setOf("yen", "yuan", "won", "ringgit", "rupiah", "baht") -> word.dropLast(1)
        else -> word
    }

    private suspend fun fetchLatestRate(
        amount: BigDecimal,
        fromCurrency: ResolvedCurrency,
        toCurrency: ResolvedCurrency,
        today: LocalDate,
    ): Result = withContext(Dispatchers.IO) {
        val url = "$RATE_URL_PREFIX?base=${fromCurrency.code}&symbols=${toCurrency.code}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KernelAI/1.0 (Android)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalArgumentException(parseApiError(body))
            }
            val json = JSONObject(body)
            val date = LocalDate.parse(
                json.optString("date").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(
                        "Currency rates are unavailable right now. I can't do a truthful conversion offline.",
                    ),
            )
            if (ChronoUnit.DAYS.between(date, today) > MAX_RATE_AGE_DAYS) {
                throw IllegalArgumentException(
                    "Latest available ${fromCurrency.code} to ${toCurrency.code} rate is from $date, which is too stale to use.",
                )
            }
            val rateValue = json.optJSONObject("rates")?.optDouble(toCurrency.code)
            if (rateValue == null || rateValue.isNaN()) {
                throw IllegalArgumentException(
                    "Could not find a ${fromCurrency.code} to ${toCurrency.code} exchange rate.",
                )
            }
            val rate = BigDecimal.valueOf(rateValue)
            val output = amount.multiply(rate)
            Result(
                inputAmount = amount,
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                outputAmount = output,
                rate = rate,
                rateDate = date,
                sourceLabel = "ECB reference rate via Frankfurter",
            )
        }
    }

    private fun parseApiError(body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return when {
            message != null -> "Currency conversion failed: $message"
            else -> "Currency rates are unavailable right now. I can't do a truthful conversion offline."
        }
    }
}
