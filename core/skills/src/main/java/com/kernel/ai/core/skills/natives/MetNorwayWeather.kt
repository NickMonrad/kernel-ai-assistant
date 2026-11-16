package com.kernel.ai.core.skills.natives

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MET Norway Locationforecast 2.0 as the second weather source, used only when Open-Meteo fails (#1922).
 *
 * MET data is converted into the Open-Meteo JSON shape the existing parsers and cache already consume, so
 * the card, spoken reply and stale-cache replay look the same whichever provider answered. Converted bodies
 * carry `"source": "met.no"`, which drives the attribution line. Pure helpers only; HTTP lives in
 * [GetWeatherSkill].
 */

internal const val METNO_FORECAST_URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"

/** MET Norway requires an identifying User-Agent with a contact URL; never a personal address (#1922). */
internal const val METNO_USER_AGENT = "JandalAI/1.0 https://github.com/NickMonrad/kernel-ai-assistant"

/** Value of the top-level `source` key in a converted MET body. */
internal const val METNO_SOURCE = "met.no"

/** Attribution shown on the card and under the text reply when MET data answered (CC BY 4.0). */
internal const val METNO_ATTRIBUTION = "Weather data: MET Norway (CC BY 4.0)"

/** Back-off when MET answers 429 without a usable Retry-After (#1922). */
internal const val METNO_DEFAULT_BACKOFF_MS = 60_000L

/** Retry-After values are capped so a bad header cannot silence the fallback for long. */
internal const val METNO_MAX_BACKOFF_MS = 3_600_000L

internal const val WMO_UNKNOWN = -1

/** Marker file (in the app's files dir) that makes Open-Meteo requests fail on debuggable builds (#1922). */
internal const val DEBUG_OPEN_METEO_FAILURE_MARKER = "debug-force-open-meteo-failure"

/**
 * MET asks for at most 4 decimals. Truncated, not rounded. The device location is already rounded to 2
 * decimals, so this adds no precision to a GPS request.
 */
internal fun metNoCoordinate(value: Double): String =
    BigDecimal.valueOf(value).setScale(4, RoundingMode.DOWN).toPlainString()

internal fun metNoRequestUrl(lat: Double, lon: Double): String =
    "$METNO_FORECAST_URL?lat=${metNoCoordinate(lat)}&lon=${metNoCoordinate(lon)}"

/** Parses an HTTP-date header (Expires, Last-Modified) to epoch millis. Null when absent or malformed. */
internal fun parseHttpDateMillis(value: String?): Long? =
    value?.takeIf { it.isNotBlank() }?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

/** Retry-After in seconds, converted to a capped back-off in millis. */
internal fun metNoBackoffMillis(retryAfter: String?): Long =
    retryAfter?.trim()?.toLongOrNull()?.times(1_000L)?.coerceIn(1_000L, METNO_MAX_BACKOFF_MS)
        ?: METNO_DEFAULT_BACKOFF_MS

/**
 * MET symbol code (e.g. `partlycloudy_day`, `lightrainshowers_night`, `rainandthunder`) to the WMO code the
 * app already maps to emoji and description. Unknown codes return [WMO_UNKNOWN].
 */
internal fun metNoWmoCode(symbolCode: String?): Int {
    val base = symbolCode?.substringBefore('_')?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() } ?: return WMO_UNKNOWN
    if (base.endsWith("andthunder")) return 95
    return when (base) {
        "clearsky" -> 0
        "fair" -> 1
        "partlycloudy" -> 2
        "cloudy" -> 3
        "fog" -> 45
        "lightrain" -> 61
        "rain" -> 63
        "heavyrain" -> 65
        "lightrainshowers" -> 80
        "rainshowers" -> 81
        "heavyrainshowers" -> 82
        "lightsleet", "sleet", "lightsleetshowers", "sleetshowers" -> 66
        "heavysleet", "heavysleetshowers" -> 67
        "lightsnow" -> 71
        "snow" -> 73
        "heavysnow" -> 75
        "lightsnowshowers", "snowshowers" -> 85
        "heavysnowshowers" -> 86
        else -> WMO_UNKNOWN
    }
}

/** One MET timeseries entry. Precipitation and symbol come from the 1-hour window when present, else 6-hour. */
internal data class MetNoPoint(
    val time: Instant,
    val airTemperature: Double?,
    val humidity: Double?,
    val windSpeed: Double?,
    val symbolCode: String?,
    val precipitationMm: Double?,
)

/**
 * Parses MET timeseries. Throws when the units differ from metric, since the converted values would be
 * labelled wrongly. A missing `meta.units` block is accepted: MET's compact product is metric by default.
 */
internal fun parseMetNoSeries(raw: String): List<MetNoPoint> {
    val properties = JSONObject(raw).getJSONObject("properties")
    properties.optJSONObject("meta")?.optJSONObject("units")?.let { units ->
        require(units.optString("air_temperature", "celsius") == "celsius") { "unexpected temperature unit" }
        require(units.optString("wind_speed", "m/s") == "m/s") { "unexpected wind unit" }
        require(units.optString("precipitation_amount", "mm") == "mm") { "unexpected precipitation unit" }
    }
    val series = properties.getJSONArray("timeseries")
    return (0 until series.length()).map { i ->
        val entry = series.getJSONObject(i)
        val data = entry.getJSONObject("data")
        val details = data.getJSONObject("instant").getJSONObject("details")
        val window = data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours")
        MetNoPoint(
            time = Instant.parse(entry.getString("time")),
            airTemperature = details.optDoubleOrNull("air_temperature"),
            humidity = details.optDoubleOrNull("relative_humidity"),
            windSpeed = details.optDoubleOrNull("wind_speed"),
            symbolCode = window?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() },
            precipitationMm = window?.optJSONObject("details")?.optDoubleOrNull("precipitation_amount"),
        )
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

/** One local calendar day from the MET series. */
internal data class MetNoDay(
    val date: LocalDate,
    val high: Double?,
    val low: Double?,
    val precipMm: Double,
    val wmoCode: Int,
)

/**
 * Groups the series into local days starting at [fromDate]. Today's values cover only the hours from now
 * on, because MET has no earlier observations. A day with no temperature is skipped.
 */
internal fun metNoDays(points: List<MetNoPoint>, zone: ZoneId, fromDate: LocalDate, maxDays: Int): List<MetNoDay> =
    points.groupBy { it.time.atZone(zone).toLocalDate() }
        .filterKeys { !it.isBefore(fromDate) }
        .toSortedMap()
        .entries
        .take(maxDays)
        .mapNotNull { (date, pts) ->
            val temps = pts.mapNotNull { it.airTemperature }
            if (temps.isEmpty()) return@mapNotNull null
            // Daily symbol: the entry nearest midday that has one.
            val symbol = pts.filter { it.symbolCode != null }
                .minByOrNull { abs(it.time.atZone(zone).hour - 12) }?.symbolCode
            MetNoDay(
                date = date,
                high = temps.max(),
                low = temps.min(),
                precipMm = pts.sumOf { it.precipitationMm ?: 0.0 },
                wmoCode = metNoWmoCode(symbol),
            )
        }

private fun dailyObject(days: List<MetNoDay>): JSONObject {
    val time = JSONArray()
    val highs = JSONArray()
    val lows = JSONArray()
    val precip = JSONArray()
    val codes = JSONArray()
    for (day in days) {
        time.put(day.date.toString())
        highs.put(day.high ?: JSONObject.NULL)
        lows.put(day.low ?: JSONObject.NULL)
        precip.put(day.precipMm)
        codes.put(day.wmoCode)
    }
    return JSONObject()
        .put("time", time)
        .put("temperature_2m_max", highs)
        .put("temperature_2m_min", lows)
        .put("precipitation_sum", precip)
        .put("weather_code", codes)
}

/**
 * Converts a MET body to the Open-Meteo daily shape for [days] local days from today. Returns null when no
 * day has data, so the caller treats it as a failed provider.
 */
internal fun metNoDailyJson(raw: String, days: Int, zone: ZoneId, nowMillis: Long): String? {
    val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val rows = metNoDays(parseMetNoSeries(raw), zone, today, days.coerceAtLeast(1))
    if (rows.isEmpty()) return null
    return JSONObject().put("source", METNO_SOURCE).put("daily", dailyObject(rows)).toString()
}

/**
 * Converts a MET body to the Open-Meteo current-conditions shape. The first timeseries entry is the current
 * hour. Precipitation is the next hour's amount, and there is no feels-like, UV or rain probability. Those
 * fields stay absent, so the card omits them. Returns null when there is no current entry.
 */
internal fun metNoCurrentJson(raw: String, zone: ZoneId, nowMillis: Long): String? {
    val series = parseMetNoSeries(raw)
    val first = series.firstOrNull() ?: return null
    val current = JSONObject()
    first.airTemperature?.let { current.put("temperature_2m", it) }
    first.humidity?.let { current.put("relative_humidity_2m", it.roundToInt()) }
    first.windSpeed?.let { current.put("wind_speed_10m", it) }
    first.precipitationMm?.let { current.put("precipitation", it) }
    current.put("weather_code", metNoWmoCode(first.symbolCode))
    val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val todayRows = metNoDays(series, zone, today, maxDays = 1)
    return JSONObject()
        .put("source", METNO_SOURCE)
        .put("current", current)
        .apply { if (todayRows.isNotEmpty()) put("daily", dailyObject(todayRows)) }
        .toString()
}

/** Attribution for a parsed weather body: set only when MET Norway supplied it (#1922). */
internal fun weatherAttributionFor(obj: JSONObject): String? =
    if (obj.optString("source") == METNO_SOURCE) METNO_ATTRIBUTION else null

/** Appends the attribution line to the text reply for MET-sourced bodies, unchanged otherwise. */
internal fun withWeatherAttribution(text: String, obj: JSONObject): String =
    weatherAttributionFor(obj)?.let { "$text\n$it" } ?: text

/**
 * Debug-only: makes Open-Meteo requests fail while [DEBUG_OPEN_METEO_FAILURE_MARKER] exists in the app's
 * files dir, to exercise the MET Norway fallback on a device (#1922). Inert on release builds, which are not
 * debuggable. Enable with `run-as <debug package> touch files/<marker>`.
 */
internal class DebugOpenMeteoFailureInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isDebuggable() && request.url.host == "api.open-meteo.com" &&
            File(context.filesDir, DEBUG_OPEN_METEO_FAILURE_MARKER).exists()
        ) {
            throw SocketTimeoutException("debug: forced Open-Meteo failure")
        }
        return chain.proceed(request)
    }

    private fun isDebuggable(): Boolean =
        (context.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
