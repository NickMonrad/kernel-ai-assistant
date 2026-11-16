package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.permissions.CapabilityKey
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.ToolPresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
/**
 * Round coordinates obtained from device location before they are used in outbound
 * requests. Named-place coordinates must bypass this function.
 */
internal fun roundDeviceLocationCoordinate(coordinate: Double): Double =
    (coordinate * 100).roundToInt() / 100.0

internal fun openMeteoCoordinateQuery(latitude: Double, longitude: Double): String =
    "latitude=$latitude&longitude=$longitude"

internal fun nominatimReverseCoordinateQuery(latitude: Double, longitude: Double): String =
    "lat=$latitude&lon=$longitude"

// ── Top-level internal helpers (extracted for JVM testability) ────────────────

/** Format an ISO date string "YYYY-MM-DD" to "EEE d MMM" (e.g. "Mon 1 Jun"). */
internal fun formatForecastDate(dateStr: String): String {
    return try {
        val parts = dateStr.split("-")
        if (parts.size != 3) return dateStr
        val year = parts[0].toInt()
        val month = parts[1].toInt() - 1  // 0-based
        val day = parts[2].toInt()
        val cal = java.util.Calendar.getInstance().apply { set(year, month, day) }
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        "${dayNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]} $day ${monthNames[month]}"
    } catch (e: Exception) {
        dateStr
    }
}

/** Round a Double to nearest int and format as "X degrees". */
internal fun Double.toRoundedDegreesText(): String = "${roundToInt()} degrees"

/** Map UV index to human-readable label. */
internal fun uvIndexLabel(uv: Double): String = when {
    uv <= 2 -> "Low"
    uv <= 5 -> "Moderate"
    uv <= 7 -> "High"
    uv <= 10 -> "Very High"
    else -> "Extreme"
}

/** Label used when no place name resolves. Never raw coordinates (#1694). */
internal const val UNRESOLVED_LOCATION_LABEL = "your location"

/** Strip country/region suffix from location label for speech output. */
internal fun locationForSpeech(locationLabel: String?): String =
    locationLabel?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() } ?: UNRESOLVED_LOCATION_LABEL

/** Build a natural-language spoken summary for current weather. */
internal fun buildCurrentWeatherSpoken(
    locationLabel: String?,
    description: String,
    temp: Double,
    feelsLike: Double,
    tempMax: Double?,
    tempMin: Double?,
): String {
    val place = locationForSpeech(locationLabel)
    val headline = buildList {
        if (!temp.isNaN()) add("it's ${temp.toRoundedDegreesText()}")
        if (description.isNotBlank() && description != "Unknown") add(description.lowercase())
        if (!feelsLike.isNaN()) add("feeling like ${feelsLike.toRoundedDegreesText()}")
    }
    val highLow = buildList {
        tempMax?.let { add("high is ${it.toRoundedDegreesText()}") }
        tempMin?.let { add("low is ${it.toRoundedDegreesText()}") }
    }
    return buildString {
        append(
            if (headline.isNotEmpty()) {
                "In $place, ${headline.joinToString(", ")}."
            } else {
                "Here's the weather for $place."
            },
        )
        if (highLow.isNotEmpty()) append(" Today's ${highLow.joinToString(", ")}.")
    }
}

/** Build a spoken summary for a multi-day forecast (caps at 3 days). */
internal fun buildMultiDayForecastSpoken(
    locationLabel: String?,
    days: List<Triple<String, String, Pair<Double?, Double?>>>,
): String {
    val place = locationForSpeech(locationLabel)
    val daySlice = days.take(3)
    return buildString {
        append("$place ${daySlice.size}-day forecast.")
        for ((date, description, temps) in daySlice) {
            val (high, low) = temps
            val parts = buildList {
                if (description.isNotBlank() && description != "Unknown") add(description.lowercase())
                if (high != null && !high.isNaN()) add("high ${high.toRoundedDegreesText()}")
                if (low != null && !low.isNaN()) add("low ${low.toRoundedDegreesText()}")
            }
            append(" $date")
            if (parts.isNotEmpty()) append(": ${parts.joinToString(", ")}")
            append(".")
        }
    }
}

/** Build a spoken summary for a single-day forecast. */
internal fun buildSingleDayForecastSpoken(
    locationLabel: String?,
    dayLabel: String,
    description: String,
    high: Double,
    low: Double,
): String {
    val place = locationForSpeech(locationLabel)
    val parts = buildList {
        if (description.isNotBlank() && description != "Unknown") add(description.lowercase())
        if (!high.isNaN()) add("high ${high.toRoundedDegreesText()}")
        if (!low.isNaN()) add("low ${low.toRoundedDegreesText()}")
    }
    return buildString {
        append("$dayLabel in $place")
        if (parts.isNotEmpty()) append(": ${parts.joinToString(", ")}")
        append(".")
    }
}

/** Build a spoken summary for a weekend forecast (Saturday + Sunday of the intended weekend). */
internal fun buildWeekendForecastSpoken(
    locationLabel: String?,
    days: List<Triple<String, String, Pair<Double?, Double?>>>,
): String {
    val place = locationForSpeech(locationLabel)
    return buildString {
        append("$place weekend forecast.")
        for ((date, description, temps) in days) {
            val (high, low) = temps
            val parts = buildList {
                if (description.isNotBlank() && description != "Unknown") add(description.lowercase())
                if (high != null && !high.isNaN()) add("high ${high.toRoundedDegreesText()}")
                if (low != null && !low.isNaN()) add("low ${low.toRoundedDegreesText()}")
            }
            append(" $date")
            if (parts.isNotEmpty()) append(": ${parts.joinToString(", ")}")
            append(".")
        }
    }
}

/**
 * #1455 — select the indices of the intended weekend days from the daily dates returned
 * by the weather provider. Derived from the actual returned dates rather than a general
 * natural-language date parser:
 *
 * - forecast starting on Saturday → the current weekend (Saturday + following Sunday);
 * - forecast starting on Sunday → the current weekend's remaining day (Sunday only);
 * - otherwise → the next Saturday and the Sunday immediately after it.
 *
 * Only the intended weekend day(s) are returned — never the intervening weekdays — and
 * a day that falls outside the returned horizon is never fabricated.
 */
internal fun selectWeekendDayIndices(dates: List<String>): List<Int> {
    if (dates.isEmpty()) return emptyList()
    val parsed = dates.map { runCatching { LocalDate.parse(it) }.getOrNull() }
    val first = parsed[0] ?: return emptyList()
    return when (first.dayOfWeek) {
        DayOfWeek.SATURDAY -> buildList {
            add(0)
            if (parsed.getOrNull(1)?.dayOfWeek == DayOfWeek.SUNDAY) add(1)
        }
        DayOfWeek.SUNDAY -> listOf(0)
        else -> {
            val saturdayIndex = parsed.indexOfFirst { it?.dayOfWeek == DayOfWeek.SATURDAY }
            if (saturdayIndex < 0) return emptyList()
            buildList {
                add(saturdayIndex)
                if (parsed.getOrNull(saturdayIndex + 1)?.dayOfWeek == DayOfWeek.SUNDAY) {
                    add(saturdayIndex + 1)
                }
            }
        }
    }
}

/** WMO weather code to emoji mapping. */
internal fun wmoEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53 -> "🌦️"
    55 -> "🌧️"
    61, 63, 65 -> "🌧️"
    66, 67 -> "🌧️"
    71, 73, 75 -> "❄️"
    77 -> "🌨️"
    80, 81 -> "🌦️"
    82 -> "⛈️"
    85, 86 -> "🌨️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

/** WMO weather code to human-readable description. */
internal fun wmoDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snow"
    77 -> "Snow grains"
    80, 81 -> "Rain showers"
    82 -> "Heavy rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    else -> "Unknown"
}

/** A geocoding match, its country code and Nominatim importance (#1894). */
internal data class GeocodeCandidate(
    val lat: Double,
    val lon: Double,
    val countryCode: String?,
    val importance: Double?,
)

/** Geocoding outcome; separates "no such place" from "service unreachable". */
internal sealed class GeocodeOutcome {
    data class Found(val candidate: GeocodeCandidate) : GeocodeOutcome()
    data object NotFound : GeocodeOutcome()
    data object Unavailable : GeocodeOutcome()
}

/** HTTP error status from a request. 4xx responses are not retried (#1894). */
internal class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

internal fun parseGeocodeCandidate(obj: JSONObject?): GeocodeCandidate? {
    if (obj == null) return null
    val lat = obj.optString("lat").toDoubleOrNull() ?: return null
    val lon = obj.optString("lon").toDoubleOrNull() ?: return null
    return GeocodeCandidate(
        lat = lat,
        lon = lon,
        countryCode = obj.optJSONObject("address")?.optString("country_code")?.takeIf { it.isNotBlank() },
        importance = if (obj.has("importance")) obj.optDouble("importance") else null,
    )
}

/** Home-country match wins only when its importance is at least this share of the top match's. */
internal const val HOME_COUNTRY_IMPORTANCE_RATIO = 0.8

/**
 * Provider order is relevance order. Prefer the best home-country match only when it is nearly as
 * important as the overall top match, so "Paris" stays Paris, France for an AU, NZ or US user.
 */
internal fun rankGeocodeCandidates(
    candidates: List<GeocodeCandidate>,
    homeCountryCode: String?,
): GeocodeCandidate? {
    val top = candidates.firstOrNull() ?: return null
    val home = homeCountryCode?.let { code -> candidates.firstOrNull { it.countryCode.equals(code, ignoreCase = true) } }
    val topImportance = top.importance ?: return top
    val homeImportance = home?.importance ?: return top
    return if (home != null && homeImportance >= topImportance * HOME_COUNTRY_IMPORTANCE_RATIO) home else top
}

/** English country name for an ISO code, locale-independent (#1894). Null if unknown. */
internal fun englishCountryName(countryCode: String): String? {
    val upper = countryCode.uppercase()
    return java.util.Locale("", upper).getDisplayCountry(java.util.Locale.ENGLISH)
        .takeIf { it.isNotBlank() && it != upper }
}

/** Append the English country name when the match is outside the home country (#1894). */
internal fun weatherLocationLabel(
    query: String,
    candidate: GeocodeCandidate,
    homeCountryCode: String?,
): String {
    val code = candidate.countryCode ?: return query
    if (homeCountryCode == null || code.equals(homeCountryCode, ignoreCase = true)) return query
    val name = englishCountryName(code) ?: return query
    return "$query, $name"
}
// ── Weather lookup mode and failure reason helpers ──────────────────────────

/**
 * Describes whether a weather lookup targets the device's current location
 * or a user-specified named location.
 */
internal enum class WeatherLookupMode {
    DEVICE_LOCATION,
    NAMED_LOCATION,
}

/** Determine the lookup mode from the optional location argument. */
internal fun weatherLookupMode(location: String?): WeatherLookupMode =
    if (location.isNullOrBlank()) WeatherLookupMode.DEVICE_LOCATION
    else WeatherLookupMode.NAMED_LOCATION

/** Categorised reason why a fresh weather fetch failed. */
internal enum class WeatherLookupFailureReason {
    LOCATION_PERMISSION_DENIED,
    CURRENT_LOCATION_UNAVAILABLE,
    NAMED_LOCATION_NOT_FOUND,
    API_UNAVAILABLE,
    GEOCODE_UNAVAILABLE,
}

/** Human-readable user-facing message for each failure reason. */
internal fun weatherFailureMessage(reason: WeatherLookupFailureReason): String = when (reason) {
    WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED ->
        "I need Location permission to get weather for where you are now. You can enable Location in App Permissions, or ask for a city, like \"weather in Brisbane\"."
    WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE ->
        "I couldn't get your current location right now. Try again in a moment, or ask for a city, like \"weather in Brisbane\"."
    WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND ->
        "I couldn't find that location for weather. Try another city, like \"weather in Brisbane\"."
    WeatherLookupFailureReason.API_UNAVAILABLE ->
        "The weather service isn't answering right now. Try again in a few minutes."
    WeatherLookupFailureReason.GEOCODE_UNAVAILABLE ->
        "I couldn't reach the location service just now. Please try again in a moment."
}

/** #1921 — a cached entry is usable when its age is within [maxAgeMillis]; a negative age (clock moved back) is not. */
internal fun isCacheEntryUsable(cachedAtMillis: Long, nowMillis: Long, maxAgeMillis: Long): Boolean =
    (nowMillis - cachedAtMillis) in 0..maxAgeMillis

/**
 * Forecast body for one place and shape. [displayName] keeps the place label for replays (#1921).
 * [validUntilMillis] and [lastModified] are set only for MET Norway bodies: the body is not served fresh past
 * MET's Expires, and [lastModified] drives If-Modified-Since (#1922).
 */
internal data class WeatherCacheEntry(
    val cachedAtMillis: Long,
    val displayName: String?,
    val body: String,
    val validUntilMillis: Long? = null,
    val lastModified: String? = null,
)

internal fun encodeWeatherCacheEntry(entry: WeatherCacheEntry): String =
    JSONObject()
        .put("cachedAt", entry.cachedAtMillis)
        .put("displayName", entry.displayName ?: JSONObject.NULL)
        .put("body", entry.body)
        .put("validUntil", entry.validUntilMillis ?: JSONObject.NULL)
        .put("lastModified", entry.lastModified ?: JSONObject.NULL)
        .toString()

internal fun decodeWeatherCacheEntry(raw: String): WeatherCacheEntry? = try {
    val obj = JSONObject(raw)
    WeatherCacheEntry(
        cachedAtMillis = obj.getLong("cachedAt"),
        displayName = obj.optString("displayName").takeIf { it.isNotBlank() },
        body = obj.getString("body"),
        validUntilMillis = if (obj.isNull("validUntil")) null else obj.getLong("validUntil"),
        lastModified = obj.optString("lastModified").takeIf { it.isNotBlank() },
    )
} catch (e: Exception) {
    null
}

/** Mutable clock for cache ages; tests replace it to simulate elapsed time (#1921). */
internal var weatherClockMillis: () -> Long = System::currentTimeMillis

/**
 * Local time of a cached forecast for speech and cards, e.g. "9:40 AM" or, from an earlier day,
 * "Sat 14 Nov 9:40 AM" (#1921). [is24Hour] follows the device clock setting.
 */
internal fun staleForecastTimeText(
    cachedAtMillis: Long,
    nowMillis: Long,
    is24Hour: Boolean,
    zone: java.time.ZoneId,
): String {
    val cached = java.time.Instant.ofEpochMilli(cachedAtMillis).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val clock = if (is24Hour) "HH:mm" else "h:mm a"
    val pattern = if (cached.toLocalDate() == today) clock else "EEE d MMM $clock"
    return cached.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH))
}

/**
 * #1921 — label a cached forecast served after a live failure. Content, spoken summary and card all
 * carry the time. Returns null when [result] is not a forecast, so an unlabelled stale result is never served.
 */
internal fun markStaleWeather(result: SkillResult?, timeText: String): SkillResult? {
    if (result !is SkillResult.DirectReply) return null
    val lead = "The weather service isn't answering, so here's the forecast from $timeText"
    return SkillResult.DirectReply(
        content = "$lead:\n${result.content}",
        presentation = (result.presentation as? ToolPresentation.Weather)?.copy(
            staleNoteText = "Forecast from $timeText. The weather service isn't answering, so it may be out of date.",
        ) ?: result.presentation,
        spokenSummary = "$lead: ${result.spokenSummary ?: result.content}",
    )
}

/**
 * Cache key for one lookup shape. Day-relative shapes ("tomorrow", "weekend") carry the local date so a
 * key from yesterday cannot answer today's question across midnight (#1921).
 */
internal fun weatherCacheKey(
    lookupMode: WeatherLookupMode,
    location: String?,
    weekend: Boolean,
    dayParam: String?,
    forecastDays: Int,
    nowMillis: Long,
    zone: java.time.ZoneId,
): String {
    val place = when (lookupMode) {
        WeatherLookupMode.NAMED_LOCATION -> "loc:${location.orEmpty()}"
        WeatherLookupMode.DEVICE_LOCATION -> "gps"
    }
    val date = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()
    return when {
        weekend -> "$place:weekend:$date"
        dayParam == "tomorrow" -> "$place:tomorrow:$date"
        forecastDays > 0 -> "$place:forecast:$forecastDays"
        else -> "$place:current"
    }
}

/**
 * True when a stored entry cannot be decoded or is older than the stale window (#1921). Such entries are
 * pruned on each cache write so per-place keys cannot accumulate.
 */
internal fun isWeatherEntryPrunable(raw: Any, nowMillis: Long): Boolean {
    val entry = (raw as? String)?.let(::decodeWeatherCacheEntry) ?: return true
    val age = nowMillis - entry.cachedAtMillis
    return age > STALE_CACHE_MAX_AGE_MS || age < -FUTURE_CACHE_SLACK_MS
}

/**
 * Drops daily rows dated before [today] from a cached multi-day body, so a stale replay never presents
 * yesterday as the headline or first day (#1921). Returns null when no row remains. Uses the device date,
 * not the place's date; adequate for NZ/AU but not for distant time zones.
 */
internal fun keepDailyRowsFrom(json: String, today: java.time.LocalDate): String? {
    val obj = JSONObject(json)
    val daily = obj.optJSONObject("daily") ?: return json
    val times = daily.optJSONArray("time") ?: return json
    val keep = (0 until times.length()).filter { !java.time.LocalDate.parse(times.getString(it)).isBefore(today) }
    if (keep.isEmpty()) return null
    if (keep.size == times.length()) return json
    for (key in daily.keys().asSequence().toList()) {
        val arr = daily.optJSONArray(key) ?: continue
        val rebuilt = JSONArray()
        keep.filter { it < arr.length() }.forEach { rebuilt.put(arr.get(it)) }
        daily.put(key, rebuilt)
    }
    return obj.toString()
}

/**
 * Current conditions keep the cached temperature but must not show a past day's high/low (#1921).
 * Removes the `daily` block when its first row is not [today].
 */
internal fun dropStaleCurrentDaily(json: String, today: java.time.LocalDate): String {
    val obj = JSONObject(json)
    val first = obj.optJSONObject("daily")?.optJSONArray("time")?.optString(0)?.takeIf { it.isNotBlank() }
        ?: return json
    if (java.time.LocalDate.parse(first) == today) return json
    obj.remove("daily")
    return obj.toString()
}

// ── Weather cache constants ──────────────────────────────────────────────────

/** Age (ms) within which a cached forecast is served without a live API call. */
private const val CACHE_MAX_AGE_MS = 2 * 60 * 60 * 1000L // 2 hours

/** Age (ms) up to which a cached forecast may replace a failed live fetch (#1921). */
private const val STALE_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L // 12 hours

/** Entries stamped more than this far in the future are treated as corrupt and pruned (#1921 N5). */
private const val FUTURE_CACHE_SLACK_MS = 60_000L

/** Budget (ms) for the Open-Meteo attempt before MET Norway is tried, so the fallback fits in 10 s (#1922). */
private const val OPEN_METEO_BUDGET_MS = 5_000L

/** Budget (ms) for geocoding, forecast and current conditions; air quality has its own budget (#1921). */
private const val WEATHER_LIVE_BUDGET_MS = 10_000L

/** Budget (ms) for the optional air-quality call, which runs after the forecast budget (#1921). */
private const val AIR_QUALITY_BUDGET_MS = 3_000L

/** Hard cap (ms) on one weather HTTP call, including its body read, so a stalled call cannot outlive the budget (#1921). */
private const val WEATHER_CALL_TIMEOUT_MS = WEATHER_LIVE_BUDGET_MS

/** Per-request timeouts for weather calls (#1921). */
private const val WEATHER_CONNECT_TIMEOUT_MS = 4_000L
private const val WEATHER_READ_TIMEOUT_MS = 6_000L

/** One retry for transient failures; the live budget also bounds it (#1921). */
private const val RETRY_MAX_ATTEMPTS = 1

/** Delay before the retry (ms). */
private const val RETRY_BASE_DELAY_MS = 500L

/** DataStore key prefix: one entry per cache key holding {cachedAt, displayName, body} (#1921). */
private const val WEATHER_CACHE_ENTRY_PREFIX = "weather_entry:"

/** Extension property providing the weather-specific DataStore. */
private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather_cache")

private const val TAG = "KernelAI"

/**
 * #1455 — parse a daily Open-Meteo forecast response restricted to the intended
 * weekend days (see [selectWeekendDayIndices]). Pure function of the response JSON,
 * so fresh fetches and stale-cache fallback always select the same weekend days.
 */
internal fun parseWeekendResponse(json: String, displayName: String?, skillName: String = "get_weather_gps"): SkillResult {
    val obj = JSONObject(json)
    val daily = obj.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val maxTemps = daily.getJSONArray("temperature_2m_max")
    val minTemps = daily.getJSONArray("temperature_2m_min")
    val precip = daily.getJSONArray("precipitation_sum")
    val codes = daily.getJSONArray("weather_code")
    val uvMaxArr = daily.optJSONArray("uv_index_max")
    val sunriseArr = daily.optJSONArray("sunrise")
    val sunsetArr = daily.optJSONArray("sunset")

    val len = dates.length()
    if (len == 0) return SkillResult.Failure(skillName, "No forecast data returned.")
    if (maxTemps.length() != len || minTemps.length() != len ||
        precip.length() != len || codes.length() != len
    ) {
        return SkillResult.Failure(skillName, "Incomplete forecast data (mismatched array lengths).")
    }

    val indices = selectWeekendDayIndices((0 until len).map { dates.getString(it) })
    if (indices.isEmpty()) {
        return SkillResult.Failure(skillName, "Weekend forecast not available within the forecast horizon.")
    }

    val locationLabel = displayName ?: UNRESOLVED_LOCATION_LABEL
    val text = buildString {
        append("$locationLabel weekend forecast:\n")
        for (i in indices) {
            val dateStr = dates.getString(i)
            val formattedDate = formatForecastDate(dateStr)
            val code = codes.optInt(i, -1)
            val emoji = wmoEmoji(code)
            val desc = wmoDescription(code)
            val high = maxTemps.optDouble(i, Double.NaN)
            val low = minTemps.optDouble(i, Double.NaN)
            val rain = precip.optDouble(i, 0.0)
            val highStr = if (!high.isNaN()) "%.0f°C".format(high) else "?°C"
            val lowStr = if (!low.isNaN()) "%.0f°C".format(low) else "?°C"
            val rainStr = "%.0fmm rain".format(rain)
            val uvMax = uvMaxArr?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
            val uvStr = uvMax?.let { " | UV max: %.0f (%s)".format(it, uvIndexLabel(it)) } ?: ""
            val sunrise = sunriseArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
            val sunset = sunsetArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
            val sunStr = when {
                sunrise != null && sunset != null -> " | 🌅 $sunrise / $sunset"
                sunrise != null -> " | 🌅 $sunrise"
                sunset != null -> " | 🌇 $sunset"
                else -> ""
            }
            append("$formattedDate: $emoji $desc $highStr / $lowStr, $rainStr$uvStr$sunStr\n")
        }
    }.trimEnd()

    val forecastDays = indices.map { i ->
        val dateStr = dates.getString(i)
        val code = codes.optInt(i, -1)
        val high = maxTemps.optDouble(i, Double.NaN)
        val low = minTemps.optDouble(i, Double.NaN)
        val rain = precip.optDouble(i, 0.0)
        val uvMax = uvMaxArr?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
        val sunrise = sunriseArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
        val sunset = sunsetArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
        val sunStr = when {
            sunrise != null && sunset != null -> "Sunrise $sunrise • Sunset $sunset"
            sunrise != null -> "Sunrise $sunrise"
            sunset != null -> "Sunset $sunset"
            else -> null
        }
        ToolPresentation.ForecastDay(
            date = formatForecastDate(dateStr),
            emoji = wmoEmoji(code),
            description = wmoDescription(code),
            highText = if (!high.isNaN()) "High %.0f°C".format(high) else null,
            lowText = if (!low.isNaN()) "Low %.0f°C".format(low) else null,
            precipText = "%.0fmm rain".format(rain).takeIf { rain > 0.0 },
            uvText = uvMax?.let { "UV max %.0f (%s)".format(it, uvIndexLabel(it)) },
            sunText = sunStr,
        )
    }
    val spokenDays = indices.map { i ->
        Triple(
            formatForecastDate(dates.getString(i)),
            wmoDescription(codes.optInt(i, -1)),
            Pair(
                maxTemps.optDouble(i, Double.NaN).takeUnless { it.isNaN() },
                minTemps.optDouble(i, Double.NaN).takeUnless { it.isNaN() },
            ),
        )
    }

    val first = indices.first()
    val firstCode = codes.optInt(first, -1)
    val firstHigh = maxTemps.optDouble(first, Double.NaN)
    val firstLow = minTemps.optDouble(first, Double.NaN)
    val firstRain = precip.optDouble(first, Double.NaN)
    val firstUv = uvMaxArr?.let {
        if (first < it.length() && !it.isNull(first)) it.getDouble(first) else null
    }
    val firstSunrise = sunriseArr?.let {
        if (first < it.length() && !it.isNull(first)) it.getString(first).substringAfterLast("T") else null
    }
    val firstSunset = sunsetArr?.let {
        if (first < it.length() && !it.isNull(first)) it.getString(first).substringAfterLast("T") else null
    }
    val temperatureText = buildString {
        if (!firstHigh.isNaN()) append("%.0f°C".format(firstHigh))
        if (!firstLow.isNaN()) {
            if (isNotEmpty()) append(" / ")
            append("%.0f°C".format(firstLow))
        }
    }.ifBlank { "Forecast unavailable" }
    val highLowText = buildString {
        if (!firstHigh.isNaN()) append("High %.0f°C".format(firstHigh))
        if (!firstLow.isNaN()) {
            if (isNotEmpty()) append(" • ")
            append("Low %.0f°C".format(firstLow))
        }
    }.takeIf { it.isNotBlank() }
    val sunText = when {
        firstSunrise != null && firstSunset != null -> "Sunrise $firstSunrise • Sunset $firstSunset"
        firstSunrise != null -> "Sunrise $firstSunrise"
        firstSunset != null -> "Sunset $firstSunset"
        else -> null
    }
    return SkillResult.DirectReply(
        withWeatherAttribution(text, obj),
        presentation = ToolPresentation.Weather(
            locationName = locationLabel,
            temperatureText = temperatureText,
            feelsLikeText = null,
            description = wmoDescription(firstCode),
            emoji = wmoEmoji(firstCode),
            highLowText = highLowText,
            humidityText = null,
            windText = null,
            precipText = if (!firstRain.isNaN()) "%.0fmm rain".format(firstRain) else null,
            uvText = firstUv?.let { "UV max %.0f (%s)".format(it, uvIndexLabel(it)) },
            airQualityText = null,
            sunText = sunText,
            forecast = forecastDays,
            attributionText = weatherAttributionFor(obj),
        ),
        spokenSummary = buildWeekendForecastSpoken(locationLabel, spokenDays),
    )
}

/** Internal wrapper for fresh weather fetch outcomes — success, current conditions needing air quality, or a failure. */
private sealed class LiveFetchResult {
    data class Success(val result: SkillResult) : LiveFetchResult()
    data class Current(val fetch: CurrentWeatherFetch) : LiveFetchResult()
    data class Failed(val reason: WeatherLookupFailureReason) : LiveFetchResult()
}

/**
 * Current conditions fetched inside the forecast budget. Air quality is added later by
 * [GetWeatherSkill.buildCurrentWeather], outside that budget (#1921).
 */
private data class CurrentWeatherFetch(
    val body: String,
    val displayName: String?,
    val lat: Double,
    val lon: Double,
    val fromCache: Boolean,
    val fromFallback: Boolean = false,
)

@Singleton
class GetWeatherSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    sharedHttpClient: OkHttpClient,
) : Skill {
    // Weather-only timeouts (#1921); the shared client keeps its own settings for other users.
    private val httpClient: OkHttpClient = sharedHttpClient.newBuilder()
        .connectTimeout(WEATHER_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(WEATHER_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(WEATHER_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .addInterceptor(DebugOpenMeteoFailureInterceptor(context))
        .build()

    /** Earliest time MET Norway may be called again after a 429 (#1922). */
    private var metNoBackoffUntilMillis = 0L
    /** Test seam: swapped for an isolated DataStore in unit tests. */
    internal var weatherStore: DataStore<Preferences> = context.weatherDataStore

    override val name = "get_weather_gps"
    override val description =
        "Get current weather or a multi-day forecast. Uses device GPS by default — only pass a " +
            "location if the user explicitly names a place or says 'at home'. " +
            "If location permission is missing, the assistant will prompt for it. " +
            "ALWAYS call this tool for any weather question — never use weather data from memory, it is stale."
    override val examples = listOf(
        "Current location weather → get_weather_gps()",
        "GPS location 3-day forecast → get_weather_gps(forecast_days=\"3\")",
        "Weather in Brisbane → get_weather_gps(location=\"Brisbane\")",
        "Weekend forecast in Bundaberg → get_weather_gps(location=\"Bundaberg\", period=\"weekend\")",
        "Weather at home → get_weather_gps(location=\"Murrumba Downs, QLD, Australia\")",
    )

    override val schema = SkillSchema(
        parameters = mapOf(
            "location" to SkillParameter(
                type = "string",
                description = "Optional location/city name. Only provide if the user explicitly names a place or says 'at home'. Leave blank to use device GPS — GPS is always preferred and more accurate than profile location.",
            ),
            "forecast_days" to SkillParameter(
                type = "integer",
                description = "Number of forecast days (1–7). Omit for current conditions only.",
            ),
            "period" to SkillParameter(
                type = "string",
                description = "Optional forecast period: \"weekend\" targets the current/upcoming weekend " +
                    "(Saturday + Sunday, or just the remaining Sunday when asked on a Sunday). " +
                    "Omit for the default current/today behaviour. Mutually exclusive with forecast_days.",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val location = call.arguments["location"]?.trim()
        val forecastDays = call.arguments["forecast_days"]?.trim()?.toIntOrNull()?.coerceIn(1, 7) ?: 0
        val dayParam = call.arguments["day"]?.trim()?.lowercase()
        val weekendRequest = call.arguments["period"]?.trim()?.lowercase() == "weekend"
        val lookupMode = weatherLookupMode(location)

        val cacheKey = weatherCacheKey(
            lookupMode = lookupMode,
            location = location,
            weekend = weekendRequest,
            dayParam = dayParam,
            forecastDays = forecastDays,
            nowMillis = weatherClockMillis(),
            zone = ZoneId.systemDefault(),
        )

        val startedAtNanos = System.nanoTime()
        val freshResult = try {
            withContext(Dispatchers.IO) { withTimeoutOrNull(WEATHER_LIVE_BUDGET_MS) {
                when {
                    weekendRequest -> when (lookupMode) {
                        WeatherLookupMode.NAMED_LOCATION ->
                            fetchByLocationName(location.orEmpty(), targetWeekend = true, cacheKey = cacheKey)
                        WeatherLookupMode.DEVICE_LOCATION ->
                            fetchByDeviceLocation(targetWeekend = true, cacheKey = cacheKey)
                    }
                    dayParam == "tomorrow" -> {
                        val targetDays = forecastDays.coerceAtLeast(2)
                        when (lookupMode) {
                            WeatherLookupMode.NAMED_LOCATION ->
                                fetchByLocationName(location.orEmpty(), targetDays, targetDay = true, cacheKey = cacheKey)
                            WeatherLookupMode.DEVICE_LOCATION ->
                                fetchByDeviceLocation(targetDays, targetDay = true, cacheKey = cacheKey)
                        }
                    }
                    lookupMode == WeatherLookupMode.NAMED_LOCATION ->
                        fetchByLocationName(location.orEmpty(), forecastDays, cacheKey = cacheKey)
                    else ->
                        fetchByDeviceLocation(forecastDays, cacheKey = cacheKey)
                }
            } } ?: run {
                Log.w(TAG, "Live weather fetch exceeded the ${WEATHER_LIVE_BUDGET_MS} ms budget")
                LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live weather fetch crashed (${e.javaClass.simpleName})")
            LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
        }

        val failureReason = when (freshResult) {
            is LiveFetchResult.Success -> return freshResult.result
            // Air quality is fetched here, after the forecast budget (#1921).
            is LiveFetchResult.Current -> buildCurrentWeather(freshResult.fetch, startedAtNanos)?.let { return it }
                ?: WeatherLookupFailureReason.API_UNAVAILABLE
            is LiveFetchResult.Failed -> freshResult.reason
        }

        if (failureReason == WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED) {
            Log.i(TAG, "GetWeatherSkill: returning CapabilityRequired(WeatherCurrentLocation)")
            return SkillResult.CapabilityRequired(
                capabilityKey = CapabilityKey.WeatherCurrentLocation,
                skillName = name,
                contextParams = emptyMap(),
            )
        }

        // Stale fallback only for a named place whose service or geocoder is down. GPS keys have no
        // place label to show, so they never serve stale data (#1921).
        if (lookupMode == WeatherLookupMode.NAMED_LOCATION &&
            (failureReason == WeatherLookupFailureReason.API_UNAVAILABLE ||
                failureReason == WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)
        ) {
            staleWeatherReply(cacheKey, failureReason, weekendRequest, dayParam, forecastDays)?.let { return it }
        }

        Log.i(TAG, "Weather cache miss after ${failureReason.name}")
        if (failureReason == WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE) {
            // TODO(#1164): Replace this interim current-location guidance with the
            // contextual capability prompt + retry flow.
            Log.i(TAG, "Returning interim current-location guidance pending #1164")
        }
        return SkillResult.DirectReply(weatherFailureMessage(failureReason))
    }

    /**
     * Cached forecast for a named place, labelled with when it was fetched (#1921). Null when there is no
     * usable entry, the entry lacks a place label, or it cannot be re-parsed.
     */
    private suspend fun staleWeatherReply(
        cacheKey: String,
        failureReason: WeatherLookupFailureReason,
        weekendRequest: Boolean,
        dayParam: String?,
        forecastDays: Int,
    ): SkillResult? {
        val now = weatherClockMillis()
        val entry = readWeatherEntry(cacheKey)
            ?.takeIf { isCacheEntryUsable(it.cachedAtMillis, now, STALE_CACHE_MAX_AGE_MS) }
            ?.takeIf { !it.displayName.isNullOrBlank() }
            ?: return null
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val reparsed = try {
            val dailyBody = if (forecastDays > 0 || weekendRequest || dayParam == "tomorrow") {
                keepDailyRowsFrom(entry.body, today) ?: return null
            } else {
                dropStaleCurrentDaily(entry.body, today)
            }
            when {
                weekendRequest -> parseWeekendResponse(dailyBody, entry.displayName)
                dayParam == "tomorrow" -> parseForecastDayResponse(dailyBody, entry.displayName, dayIndex = 1)
                forecastDays > 0 -> parseForecastResponse(dailyBody, entry.displayName)
                else -> parseWeatherResponse(dailyBody, entry.displayName, airQuality = null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cached weather re-parse failed (${e.javaClass.simpleName})")
            return null
        }
        Log.i(TAG, "Serving stale weather cache after ${failureReason.name}")
        val timeText = staleForecastTimeText(entry.cachedAtMillis, now, uses24HourClock(), ZoneId.systemDefault())
        return markStaleWeather(reparsed, timeText)
    }

    /** Device 12/24-hour setting; falls back to 12-hour when the platform call is unavailable (unit tests). */
    private fun uses24HourClock(): Boolean =
        runCatching { android.text.format.DateFormat.is24HourFormat(context) }.getOrDefault(false)

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByLocationName(
        locationName: String,
        forecastDays: Int = 0,
        targetDay: Boolean = false,
        targetWeekend: Boolean = false,
        cacheKey: String,
    ): LiveFetchResult {
        val query = resolveIndirectLocationReference(locationName) ?: locationName
        val home = homeCountryCode()
        val candidate = when (val outcome = geocodeLocation(query, home)) {
            is GeocodeOutcome.Found -> outcome.candidate
            GeocodeOutcome.NotFound -> {
                Log.i(TAG, "Named-location weather geocode: no match")
                return LiveFetchResult.Failed(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)
            }
            GeocodeOutcome.Unavailable -> {
                Log.w(TAG, "Named-location weather geocode unavailable")
                return LiveFetchResult.Failed(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)
            }
        }
        val resolvedLocationName = weatherLocationLabel(query, candidate, home)
        val coordinates = candidate.lat to candidate.lon

        return when {
            targetWeekend -> fetchWeekendForecast(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = resolvedLocationName,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            forecastDays > 0 && targetDay -> fetchForecastForDay(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = resolvedLocationName,
                days = forecastDays,
                dayIndex = 1,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            forecastDays > 0 -> fetchForecast(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = resolvedLocationName,
                days = forecastDays,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            else -> fetchWeather(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = resolvedLocationName,
                cacheKey = cacheKey,
            )?.let { LiveFetchResult.Current(it) } ?: apiUnavailable()
        }
    }

    private fun SkillResult?.toLiveFetchResult(): LiveFetchResult =
        this?.let { LiveFetchResult.Success(it) } ?: apiUnavailable()

    private fun apiUnavailable(): LiveFetchResult.Failed {
        Log.w(TAG, "Weather API unavailable after lookup")
        return LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
    }

    private suspend fun resolveIndirectLocationReference(locationName: String): String? {
        val country = WeatherLocationReferenceParser.extractCountryFromCapitalQuery(locationName) ?: return null
        WeatherLocationReferenceParser.knownCapitalForCountry(country)?.let { return it }
        return lookupCountryCapital(country)
    }

    private suspend fun lookupCountryCapital(countryName: String): String? = withContext(Dispatchers.IO) {
        try {
            val normalizedCountry = WeatherLocationReferenceParser.normalizeCountryName(countryName)
            val url = "https://restcountries.com/v3.1/name/" +
                java.net.URLEncoder.encode(normalizedCountry, "UTF-8") +
                "?fields=capital,name"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KernelAI/1.0 (Android)")
                .build()
            executeWeatherCall(request) { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val results = org.json.JSONArray(body)
                if (results.length() == 0) return@withContext null
                val first = results.getJSONObject(0)
                val capitalArray = first.optJSONArray("capital") ?: return@withContext null
                capitalArray.optString(0).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Country capital lookup failed (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun geocodeLocation(locationName: String, homeCountryCode: String?): GeocodeOutcome =
        withContext(Dispatchers.IO) {
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=${java.net.URLEncoder.encode(locationName, "UTF-8")}" +
                "&format=json&limit=5&addressdetails=1&accept-language=en"
            val body = retryWithBackoff("Geocode", maxRetries = 1) {
                val request = Request.Builder().url(url).header("User-Agent", "KernelAI/1.0 (Android)").build()
                executeWeatherCall(request) { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    response.body?.string() ?: throw IllegalStateException("Empty geocode response")
                }
            } ?: return@withContext GeocodeOutcome.Unavailable
            try {
                val array = org.json.JSONArray(body)
                val candidates = (0 until array.length()).mapNotNull { parseGeocodeCandidate(array.optJSONObject(it)) }
                rankGeocodeCandidates(candidates, homeCountryCode)
                    ?.let { GeocodeOutcome.Found(it) } ?: GeocodeOutcome.NotFound
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "Geocode response unparseable")
                GeocodeOutcome.Unavailable
            }
        }

    /**
     * Home country for geocoding bias, resolved on-device: network country, then SIM country, then
     * the first app locale's region. No location is read and no network request is made (#1894).
     */
    private fun homeCountryCode(): String? {
        val network = runCatching { telephonyManager()?.networkCountryIso }.getOrNull()
        val sim = runCatching { telephonyManager()?.simCountryIso }.getOrNull()
        val locale = runCatching { context.resources.configuration.locales[0].country }.getOrNull()
        return listOf(network, sim, locale).firstOrNull { !it.isNullOrBlank() }?.lowercase()
    }

    private fun telephonyManager(): TelephonyManager? =
        runCatching { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull()

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByDeviceLocation(
        forecastDays: Int = 0,
        targetDay: Boolean = false,
        targetWeekend: Boolean = false,
        cacheKey: String,
    ): LiveFetchResult {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Current-location weather blocked: ACCESS_COARSE_LOCATION denied")
            return LiveFetchResult.Failed(WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED)
        }

        val loc = getLastKnownLocation()
            ?: run {
                Log.i(TAG, "Current-location weather unavailable: fused lastLocation returned null")
                return LiveFetchResult.Failed(WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE)
            }

        val requestLatitude = roundDeviceLocationCoordinate(loc.latitude)
        val requestLongitude = roundDeviceLocationCoordinate(loc.longitude)
        val displayName = reverseGeocode(requestLatitude, requestLongitude)
        return when {
            targetWeekend -> fetchWeekendForecast(
                lat = requestLatitude,
                lon = requestLongitude,
                displayName = displayName,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            forecastDays > 0 && targetDay -> fetchForecastForDay(
                lat = requestLatitude,
                lon = requestLongitude,
                displayName = displayName,
                days = forecastDays,
                dayIndex = 1,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            forecastDays > 0 -> fetchForecast(
                lat = requestLatitude,
                lon = requestLongitude,
                displayName = displayName,
                days = forecastDays,
                cacheKey = cacheKey,
            ).toLiveFetchResult()
            else -> fetchWeather(
                lat = requestLatitude,
                lon = requestLongitude,
                displayName = displayName,
                cacheKey = cacheKey,
            )?.let { LiveFetchResult.Current(it) } ?: apiUnavailable()
        }
    }

    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?${nominatimReverseCoordinateQuery(lat, lon)}&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KernelAI/1.0 (Android)")
                .build()
            executeWeatherCall(request) { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Reverse geocode failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string()
                if (body == null) {
                    Log.w(TAG, "Reverse geocode failed: empty response body")
                    return@withContext null
                }
                val json = JSONObject(body)
                val address = json.optJSONObject("address")
                if (address == null) {
                    Log.w(TAG, "Reverse geocode failed: response has no address object")
                    return@withContext null
                }
                val city = address.optString("city").takeIf { it.isNotBlank() }
                    ?: address.optString("town").takeIf { it.isNotBlank() }
                    ?: address.optString("village").takeIf { it.isNotBlank() }
                    ?: address.optString("suburb").takeIf { it.isNotBlank() }
                if (city == null) {
                    Log.w(TAG, "Reverse geocode failed: address has no city, town, village or suburb")
                    return@withContext null
                }
                val country = address.optString("country_code").uppercase().takeIf { it.isNotBlank() }
                if (country != null) "$city, $country" else city
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.javaClass.simpleName}")
            null
        }
    }

    @Suppress("MissingPermission")
    private suspend fun getLastKnownLocation(): android.location.Location? =
        suspendCancellableCoroutine { cont ->
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.lastLocation
                .addOnSuccessListener { loc -> cont.resumeWith(Result.success(loc)) }
                .addOnFailureListener { cont.resumeWith(Result.success(null)) }
        }

    // ── Forecast fetch ────────────────────────────────────────────────────────

    /**
     * #1455 — fetch enough daily forecast data to reach the nearest current/upcoming
     * weekend (the existing 7-day horizon always contains it) and restrict the parsed
     * result to the intended weekend days via [parseWeekendResponse]. The raw body is
     * cached under the request's weekend cache key, so a stale-cache fallback re-runs
     * the same weekend selection on the identical JSON.
     */
    private suspend fun fetchWeekendForecast(
        lat: Double,
        lon: Double,
        displayName: String?,
        cacheKey: String,
    ): SkillResult? =
        withContext(Dispatchers.IO) {
            // Check cache first
            getCachedWeatherJson(cacheKey)?.let { cachedJson ->
                return@withContext parseWeekendResponse(cachedJson, displayName)
            }

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?${openMeteoCoordinateQuery(lat, lon)}" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=7&wind_speed_unit=ms"

            val weather = fetchWeatherBody(url, "Weekend Forecast API", lat, lon, cacheKey) {
                metNoDailyJson(it, days = 7, zone = ZoneId.systemDefault(), nowMillis = weatherClockMillis())
            } ?: return@withContext null

            cacheParsedForecast(cacheKey, displayName, weather) { parseWeekendResponse(it, displayName) }
        }

    private suspend fun fetchForecast(
        lat: Double,
        lon: Double,
        displayName: String?,
        days: Int,
        cacheKey: String,
    ): SkillResult? =
        withContext(Dispatchers.IO) {
            // Check cache first
            getCachedWeatherJson(cacheKey)?.let { cachedJson ->
                return@withContext parseForecastResponse(cachedJson, displayName)
            }

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?${openMeteoCoordinateQuery(lat, lon)}" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=$days&wind_speed_unit=ms"

            val weather = fetchWeatherBody(url, "Forecast API ($days days)", lat, lon, cacheKey) {
                metNoDailyJson(it, days = days, zone = ZoneId.systemDefault(), nowMillis = weatherClockMillis())
            } ?: return@withContext null

            cacheParsedForecast(cacheKey, displayName, weather) { parseForecastResponse(it, displayName) }
        }

    private fun parseForecastResponse(json: String, displayName: String?): SkillResult {
        val obj = JSONObject(json)
        val daily = obj.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val maxTemps = daily.getJSONArray("temperature_2m_max")
        val minTemps = daily.getJSONArray("temperature_2m_min")
        val precip = daily.getJSONArray("precipitation_sum")
        val codes = daily.getJSONArray("weather_code")
        val uvMaxArr = daily.optJSONArray("uv_index_max")
        val sunriseArr = daily.optJSONArray("sunrise")
        val sunsetArr = daily.optJSONArray("sunset")

        val len = dates.length()
        if (len == 0) return SkillResult.Failure(name, "No forecast data returned.")
        if (maxTemps.length() != len || minTemps.length() != len ||
            precip.length() != len || codes.length() != len) {
            return SkillResult.Failure(name, "Incomplete forecast data (mismatched array lengths).")
        }

        val locationLabel = displayName ?: UNRESOLVED_LOCATION_LABEL
        val text = buildString {
            append("$locationLabel forecast:\n")
            for (i in 0 until len) {
                val dateStr = dates.getString(i)          // "YYYY-MM-DD"
                val formattedDate = formatForecastDate(dateStr)
                val code = codes.optInt(i, -1)
                val emoji = wmoEmoji(code)
                val desc = wmoDescription(code)
                val high = maxTemps.optDouble(i, Double.NaN)
                val low = minTemps.optDouble(i, Double.NaN)
                val rain = precip.optDouble(i, 0.0)
                val highStr = if (!high.isNaN()) "%.0f°C".format(high) else "?°C"
                val lowStr = if (!low.isNaN()) "%.0f°C".format(low) else "?°C"
                val rainStr = "%.0fmm rain".format(rain)
                val uvMax = uvMaxArr?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
                val uvStr = uvMax?.let { " | UV max: %.0f (%s)".format(it, uvIndexLabel(it)) } ?: ""
                val sunrise = sunriseArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
                val sunset = sunsetArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
                val sunStr = when {
                    sunrise != null && sunset != null -> " | 🌅 $sunrise / $sunset"
                    sunrise != null -> " | 🌅 $sunrise"
                    sunset != null -> " | 🌇 $sunset"
                    else -> ""
                }
                append("$formattedDate: $emoji $desc $highStr / $lowStr, $rainStr$uvStr$sunStr\n")
            }
        }.trimEnd()


        val forecastDays = (0 until len).map { i ->
            val dateStr = dates.getString(i)
            val formattedDate = formatForecastDate(dateStr)
            val code = codes.optInt(i, -1)
            val high = maxTemps.optDouble(i, Double.NaN)
            val low = minTemps.optDouble(i, Double.NaN)
            val rain = precip.optDouble(i, 0.0)
            val uvMax = uvMaxArr?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
            val sunrise = sunriseArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
            val sunset = sunsetArr?.let { if (i < it.length() && !it.isNull(i)) it.getString(i).substringAfterLast("T") else null }
            val sunStr = when {
                sunrise != null && sunset != null -> "Sunrise $sunrise • Sunset $sunset"
                sunrise != null -> "Sunrise $sunrise"
                sunset != null -> "Sunset $sunset"
                else -> null
            }
            ToolPresentation.ForecastDay(
                date = formattedDate,
                emoji = wmoEmoji(code),
                description = wmoDescription(code),
                highText = if (!high.isNaN()) "High %.0f°C".format(high) else null,
                lowText = if (!low.isNaN()) "Low %.0f°C".format(low) else null,
                precipText = "%.0fmm rain".format(rain).takeIf { rain > 0.0 },
                uvText = uvMax?.let { "UV max %.0f (%s)".format(it, uvIndexLabel(it)) },
                sunText = sunStr,
            )
        }
        val spokenDays = (0 until len).map { i ->
            val formattedDate = formatForecastDate(dates.getString(i))
            val desc = wmoDescription(codes.optInt(i, -1))
            val high = maxTemps.optDouble(i, Double.NaN)
            val low = minTemps.optDouble(i, Double.NaN)
            Triple(formattedDate, desc, Pair(high.takeUnless { it.isNaN() }, low.takeUnless { it.isNaN() }))
        }
        Log.d(TAG, "GetWeatherSkill: fetched ${len}-day forecast")
        val firstCode = codes.optInt(0, -1)
        val firstHigh = maxTemps.optDouble(0, Double.NaN)
        val firstLow = minTemps.optDouble(0, Double.NaN)
        val firstRain = precip.optDouble(0, Double.NaN)
        val firstUv = uvMaxArr?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getDouble(0) else null
        }
        val firstSunrise = sunriseArr?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getString(0).substringAfterLast("T") else null
        }
        val firstSunset = sunsetArr?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getString(0).substringAfterLast("T") else null
        }
        val temperatureText = buildString {
            if (!firstHigh.isNaN()) append("%.0f°C".format(firstHigh))
            if (!firstLow.isNaN()) {
                if (isNotEmpty()) append(" / ")
                append("%.0f°C".format(firstLow))
            }
        }.ifBlank { "Forecast unavailable" }
        val highLowText = buildString {
            if (!firstHigh.isNaN()) append("High %.0f°C".format(firstHigh))
            if (!firstLow.isNaN()) {
                if (isNotEmpty()) append(" • ")
                append("Low %.0f°C".format(firstLow))
            }
        }.takeIf { it.isNotBlank() }
        val sunText = when {
            firstSunrise != null && firstSunset != null -> "Sunrise $firstSunrise • Sunset $firstSunset"
            firstSunrise != null -> "Sunrise $firstSunrise"
            firstSunset != null -> "Sunset $firstSunset"
            else -> null
        }
        return SkillResult.DirectReply(
            withWeatherAttribution(text, obj),
            presentation = ToolPresentation.Weather(
                locationName = locationLabel,
                temperatureText = temperatureText,
                feelsLikeText = null,
                description = wmoDescription(firstCode),
                emoji = wmoEmoji(firstCode),
                highLowText = highLowText,
                humidityText = null,
                windText = null,
                precipText = if (!firstRain.isNaN()) "%.0fmm rain".format(firstRain) else null,
                uvText = firstUv?.let { "UV max %.0f (%s)".format(it, uvIndexLabel(it)) },
                airQualityText = null,
                sunText = sunText,
                forecast = forecastDays,
                attributionText = weatherAttributionFor(obj),
            ),
            spokenSummary = buildMultiDayForecastSpoken(
                locationLabel = locationLabel,
                days = spokenDays,
            ),
        )
    }

    private suspend fun fetchForecastForDay(
        lat: Double,
        lon: Double,
        displayName: String?,
        days: Int,
        dayIndex: Int,
        cacheKey: String,
    ): SkillResult? = withContext(Dispatchers.IO) {
        // Check cache first
        getCachedWeatherJson(cacheKey)?.let { cachedJson ->
            return@withContext parseForecastDayResponse(cachedJson, displayName, dayIndex)
        }

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?${openMeteoCoordinateQuery(lat, lon)}" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=$days&wind_speed_unit=ms"

        val weather = fetchWeatherBody(url, "Forecast API (day $dayIndex)", lat, lon, cacheKey) {
            metNoDailyJson(it, days = days, zone = ZoneId.systemDefault(), nowMillis = weatherClockMillis())
        } ?: return@withContext null

        cacheParsedForecast(cacheKey, displayName, weather) { parseForecastDayResponse(it, displayName, dayIndex) }
    }

    private fun parseForecastDayResponse(json: String, displayName: String?, dayIndex: Int): SkillResult {
        val obj = JSONObject(json)
        val daily = obj.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val maxTemps = daily.getJSONArray("temperature_2m_max")
        val minTemps = daily.getJSONArray("temperature_2m_min")
        val precip = daily.getJSONArray("precipitation_sum")
        val codes = daily.getJSONArray("weather_code")
        val uvMaxArr = daily.optJSONArray("uv_index_max")
        val sunriseArr = daily.optJSONArray("sunrise")
        val sunsetArr = daily.optJSONArray("sunset")

        if (dayIndex >= dates.length()) {
            return SkillResult.Failure(name, "Forecast data not available for the requested day.")
        }

        val dateStr = dates.getString(dayIndex)
        val formattedDate = formatForecastDate(dateStr)
        val code = codes.optInt(dayIndex, -1)
        val emoji = wmoEmoji(code)
        val desc = wmoDescription(code)
        val high = maxTemps.optDouble(dayIndex, Double.NaN)
        val low = minTemps.optDouble(dayIndex, Double.NaN)
        val rain = precip.optDouble(dayIndex, 0.0)
        val highStr = if (!high.isNaN()) "%.0f°C".format(high) else "?°C"
        val lowStr = if (!low.isNaN()) "%.0f°C".format(low) else "?°C"
        val rainStr = "%.0fmm rain".format(rain)
        val uvMax = uvMaxArr?.let { if (dayIndex < it.length() && !it.isNull(dayIndex)) it.getDouble(dayIndex) else null }
        val uvStr = uvMax?.let { " | UV max: %.0f (%s)".format(it, uvIndexLabel(it)) } ?: ""
        val sunrise = sunriseArr?.let { if (dayIndex < it.length() && !it.isNull(dayIndex)) it.getString(dayIndex).substringAfterLast("T") else null }
        val sunset = sunsetArr?.let { if (dayIndex < it.length() && !it.isNull(dayIndex)) it.getString(dayIndex).substringAfterLast("T") else null }
        val sunStr = when {
            sunrise != null && sunset != null -> " | 🌅 $sunrise / $sunset"
            sunrise != null -> " | 🌅 $sunrise"
            sunset != null -> " | 🌇 $sunset"
            else -> ""
        }

        val locationLabel = displayName ?: UNRESOLVED_LOCATION_LABEL
        val text = "$formattedDate: $emoji $desc $highStr / $lowStr, $rainStr$uvStr$sunStr"

        Log.d(TAG, "GetWeatherSkill: fetched forecast for day $dayIndex ($formattedDate)")

        val temperatureText = buildString {
            if (!high.isNaN()) append("%.0f°C".format(high))
            if (!low.isNaN()) {
                if (isNotEmpty()) append(" / ")
                append("%.0f°C".format(low))
            }
        }.ifBlank { "Forecast unavailable" }
        val highLowText = buildString {
            if (!high.isNaN()) append("High %.0f°C".format(high))
            if (!low.isNaN()) {
                if (isNotEmpty()) append(" • ")
                append("Low %.0f°C".format(low))
            }
        }.takeIf { it.isNotBlank() }
        val sunText = when {
            sunrise != null && sunset != null -> "Sunrise $sunrise • Sunset $sunset"
            sunrise != null -> "Sunrise $sunrise"
            sunset != null -> "Sunset $sunset"
            else -> null
        }

        return SkillResult.DirectReply(
            withWeatherAttribution(text, obj),
            presentation = ToolPresentation.Weather(
                locationName = locationLabel,
                temperatureText = temperatureText,
                feelsLikeText = null,
                description = desc,
                emoji = emoji,
                highLowText = highLowText,
                humidityText = null,
                windText = null,
                precipText = if (!rain.isNaN()) "%.0fmm rain".format(rain) else null,
                uvText = uvMax?.let { "UV max %.0f (%s)".format(it, uvIndexLabel(it)) },
                airQualityText = null,
                sunText = sunText,
                attributionText = weatherAttributionFor(obj),
            ),
            spokenSummary = buildSingleDayForecastSpoken(
                locationLabel = locationLabel,
                dayLabel = if (dayIndex == 1) "Tomorrow" else formattedDate,
                description = desc,
                high = high,
                low = low,
            ),
        )
    }


    // ── Air quality fetch ─────────────────────────────────────────────────────

    internal data class AirQualityData(val usAqi: Int?, val pm25: Double?)

    /**
     * Optional air quality for the current-weather card (#1921). One attempt, no retry, bounded by
     * [AIR_QUALITY_BUDGET_MS] at the call site. Any failure returns null so the card omits air quality.
     */
    private suspend fun fetchAirQuality(lat: Double, lon: Double): AirQualityData? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?${openMeteoCoordinateQuery(lat, lon)}&current=us_aqi,pm2_5&timezone=auto"
                val body = executeWeatherCall(Request.Builder().url(url).build()) { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string() ?: return@withContext null
                }
                val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
                val usAqi = if (current.has("us_aqi") && !current.isNull("us_aqi")) current.getInt("us_aqi") else null
                val pm25 = if (current.has("pm2_5") && !current.isNull("pm2_5")) current.getDouble("pm2_5") else null
                AirQualityData(usAqi, pm25)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Air quality unavailable (${e.javaClass.simpleName})")
                null
            }
        }

    // ── Weather fetch ─────────────────────────────────────────────────────────

    private suspend fun fetchWeather(
        lat: Double,
        lon: Double,
        displayName: String?,
        cacheKey: String,
    ): CurrentWeatherFetch? =
        withContext(Dispatchers.IO) {
            getCachedWeatherJson(cacheKey)?.let { cachedJson ->
                return@withContext CurrentWeatherFetch(cachedJson, displayName, lat, lon, fromCache = true)
            }

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?${openMeteoCoordinateQuery(lat, lon)}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m,precipitation_probability,precipitation,uv_index" +
                "&daily=uv_index_max,sunrise,sunset,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=1&timezone=auto&wind_speed_unit=ms"

            val weather = fetchWeatherBody(url, "Weather API", lat, lon, cacheKey) {
                metNoCurrentJson(it, ZoneId.systemDefault(), weatherClockMillis())
            } ?: return@withContext null

            if (!parsesOrLogs("Weather API") { parseWeatherResponse(weather.json, displayName, airQuality = null) }) {
                return@withContext null
            }
            cacheWeatherJson(cacheKey, displayName, weather.json, weather.validUntilMillis, weather.lastModified)
            CurrentWeatherFetch(weather.json, displayName, lat, lon, fromCache = false, fromFallback = weather.fromFallback)
        }

    /**
     * Builds the current-conditions answer. Air quality is fetched only for a live body, with its own
     * [AIR_QUALITY_BUDGET_MS]; the forecast budget no longer covers it, so a stalled AQ call cannot turn a
     * fresh forecast into a stale one (#1921). Returns null when the body cannot be parsed.
     */
    private suspend fun buildCurrentWeather(fetch: CurrentWeatherFetch, startedAtNanos: Long): SkillResult? {
        // Air quality is Open-Meteo only; skip it when MET answered, since Open-Meteo is down (#1922).
        val airQuality = if (fetch.fromCache || fetch.fromFallback) {
            null
        } else {
            // Air quality gets only what is left of the live budget; under 3 s it is skipped so a fresh
            // answer stays within ~10 s (#1921 N1).
            val remainingMs = WEATHER_LIVE_BUDGET_MS - (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (remainingMs < AIR_QUALITY_BUDGET_MS) null
            else withTimeoutOrNull(minOf(AIR_QUALITY_BUDGET_MS, remainingMs)) { fetchAirQuality(fetch.lat, fetch.lon) }
        }
        return try {
            parseWeatherResponse(fetch.body, fetch.displayName, airQuality)
        } catch (e: Exception) {
            Log.w(TAG, "Current weather response unparseable (${e.javaClass.simpleName})")
            null
        }
    }

    internal fun parseWeatherResponse(json: String, displayName: String?, airQuality: AirQualityData?): SkillResult {
        val obj = JSONObject(json)
        val current = obj.getJSONObject("current")

        val temp = current.optDouble("temperature_2m", Double.NaN)
        val feelsLike = current.optDouble("apparent_temperature", Double.NaN)
        val humidity = current.optInt("relative_humidity_2m", -1)
        val weatherCode = current.optInt("weather_code", -1)
        val windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
        val precipChance = current.optInt("precipitation_probability", -1)
        val precipitation = current.optDouble("precipitation", Double.NaN)
        val uvIndex = if (current.has("uv_index") && !current.isNull("uv_index"))
            current.getDouble("uv_index") else null

        // Daily fields (first element = today)
        val daily = obj.optJSONObject("daily")
        val uvIndexMax = daily?.optJSONArray("uv_index_max")?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getDouble(0) else null
        }
        val tempMax = daily?.optJSONArray("temperature_2m_max")?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getDouble(0) else null
        }
        val tempMin = daily?.optJSONArray("temperature_2m_min")?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getDouble(0) else null
        }
        val sunriseRaw = daily?.optJSONArray("sunrise")?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getString(0) else null
        }
        val sunsetRaw = daily?.optJSONArray("sunset")?.let {
            if (it.length() > 0 && !it.isNull(0)) it.getString(0) else null
        }
        val sunriseTime = sunriseRaw?.substringAfterLast("T")
        val sunsetTime = sunsetRaw?.substringAfterLast("T")

        val locationLabel = displayName ?: UNRESOLVED_LOCATION_LABEL
        val emoji = wmoEmoji(weatherCode)
        val description = wmoDescription(weatherCode)

        val text = buildString {
            // Line 1: location, temperature
            val tempStr = if (!temp.isNaN()) "%.0f°C".format(temp) else "?"
            val feelsStr = if (!feelsLike.isNaN()) "%.0f°C".format(feelsLike) else "?"
            appendLine("$emoji $locationLabel — $tempStr (feels like $feelsStr) — $description")

            // Line 2: today's high / low
            if (tempMax != null || tempMin != null) {
                val highStr = tempMax?.let { "H:%.0f°C".format(it) }
                val lowStr = tempMin?.let { "L:%.0f°C".format(it) }
                appendLine("🌡 Today: " + listOfNotNull(highStr, lowStr).joinToString(" / "))
            }

            // Line 3: humidity + wind
            val humidStr = if (humidity >= 0) "$humidity%" else null
            val windStr = if (!windSpeed.isNaN()) "%.1f m/s".format(windSpeed) else null
            if (humidStr != null || windStr != null) {
                val parts = listOfNotNull(
                    humidStr?.let { "💧 Humidity: $it" },
                    windStr?.let { "💨 Wind: $it" },
                )
                appendLine(parts.joinToString(" | "))
            }

            // Line 4: precipitation
            val precipLine = buildString {
                if (!precipitation.isNaN() && precipitation > 0.0) append("🌧 Precipitation: %.1fmm".format(precipitation))
                if (precipChance >= 0) {
                    if (isNotEmpty()) append(" | ")
                    append("☔ Chance: $precipChance%")
                }
            }
            if (precipLine.isNotEmpty()) appendLine(precipLine)

            // Line 5: UV index
            if (uvIndex != null || uvIndexMax != null) {
                val uvLine = buildString {
                    if (uvIndex != null) append("☀️ UV Index: %.0f (%s)".format(uvIndex, uvIndexLabel(uvIndex)))
                    if (uvIndexMax != null) {
                        if (isNotEmpty()) append(" | ")
                        append("Max today: %.0f".format(uvIndexMax))
                    }
                }
                appendLine(uvLine)
            }

            // Line 6: air quality
            val aqi = airQuality?.usAqi
            if (aqi != null) appendLine("🌬 Air Quality: $aqi (${aqiLabel(aqi)})")

            // Line 7: sunrise/sunset
            if (sunriseTime != null || sunsetTime != null) {
                val parts = listOfNotNull(
                    sunriseTime?.let { "🌅 Sunrise: $it" },
                    sunsetTime?.let { "Sunset: $it" },
                )
                appendLine(parts.joinToString(" | "))
            }
        }.trimEnd()

        Log.d(TAG, "GetWeatherSkill: fetched current weather")
        // DirectReply: structured data — numeric temperature/humidity/wind values
        val precipText = buildString {
            if (precipChance >= 0) append("Rain chance $precipChance%")
            if (!precipitation.isNaN()) {
                if (isNotEmpty()) append(" • ")
                append("%.1fmm".format(precipitation))
            }
        }.takeIf { it.isNotBlank() }
        val highLowText = buildString {
            tempMax?.let { append("High %.0f°C".format(it)) }
            tempMin?.let {
                if (isNotEmpty()) append(" • ")
                append("Low %.0f°C".format(it))
            }
        }.takeIf { it.isNotBlank() }
        val uvText = buildString {
            uvIndex?.let { append("UV %.0f (%s)".format(it, uvIndexLabel(it))) }
            uvIndexMax?.let {
                if (isNotEmpty()) append(" • ")
                append("Max %.0f".format(it))
            }
        }.takeIf { it.isNotBlank() }
        val sunText = when {
            sunriseTime != null && sunsetTime != null -> "Sunrise $sunriseTime • Sunset $sunsetTime"
            sunriseTime != null -> "Sunrise $sunriseTime"
            sunsetTime != null -> "Sunset $sunsetTime"
            else -> null
        }
        return SkillResult.DirectReply(
            withWeatherAttribution(text, obj),
            presentation = ToolPresentation.Weather(
                locationName = locationLabel,
                temperatureText = if (!temp.isNaN()) "%.0f°C".format(temp) else "?",
                feelsLikeText = if (!feelsLike.isNaN()) "Feels like %.0f°C".format(feelsLike) else null,
                description = description,
                emoji = emoji,
                highLowText = highLowText,
                humidityText = if (humidity >= 0) "Humidity $humidity%" else null,
                windText = if (!windSpeed.isNaN()) "Wind %.1f m/s".format(windSpeed) else null,
                precipText = precipText,
                uvText = uvText,
                airQualityText = airQuality?.usAqi?.let { "AQI $it (${aqiLabel(it)})" },
                sunText = sunText,
                attributionText = weatherAttributionFor(obj),
            ),
            spokenSummary = buildCurrentWeatherSpoken(
                locationLabel = locationLabel,
                description = description,
                temp = temp,
                feelsLike = feelsLike,
                tempMax = tempMax,
                tempMin = tempMin,
            ),
        )
    }


    private fun aqiLabel(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for Sensitive Groups"
        aqi <= 200 -> "Unhealthy"
        else -> "Very Unhealthy"
    }






    // ── Weather caching (DataStore) ────────────────────────────────────────────

    /** True when [parse] accepts the body. Logs only the exception type; bodies are never logged (#1921). */
    private fun <T> parsesOrLogs(label: String, parse: () -> T): Boolean = try {
        parse()
        true
    } catch (e: Exception) {
        Log.w(TAG, "$label response unparseable (${e.javaClass.simpleName})")
        false
    }

    /**
     * Parse [body] and cache it only when it is a forecast (#1921). A malformed body or a failure result is
     * not cached, so it cannot overwrite a good entry. A parsed failure result is still returned to the caller.
     */
    private suspend fun cacheParsedForecast(
        cacheKey: String,
        displayName: String?,
        body: WeatherBody,
        parse: (String) -> SkillResult,
    ): SkillResult? {
        val parsed = try {
            parse(body.json)
        } catch (e: Exception) {
            Log.w(TAG, "Forecast response unparseable (${e.javaClass.simpleName})")
            return null
        }
        if (parsed is SkillResult.DirectReply) {
            cacheWeatherJson(cacheKey, displayName, body.json, body.validUntilMillis, body.lastModified)
        }
        return parsed
    }


    /**
     * Store raw Open-Meteo JSON for [cacheKey] with its time and place label (#1921). Entries older than the
     * stale window are pruned in the same edit, so per-place keys cannot accumulate without bound.
     */
    private suspend fun cacheWeatherJson(
        cacheKey: String,
        displayName: String?,
        jsonBody: String,
        validUntilMillis: Long? = null,
        lastModified: String? = null,
    ) {
        val now = weatherClockMillis()
        val encoded = encodeWeatherCacheEntry(
            WeatherCacheEntry(now, displayName, jsonBody, validUntilMillis, lastModified),
        )
        weatherStore.edit { prefs ->
            // Legacy single-slot keys (pre-#1921) are dropped so only per-place entries remain.
            prefs.remove(stringPreferencesKey("weather_cache_json"))
            prefs.remove(longPreferencesKey("weather_cache_timestamp"))
            prefs.remove(stringPreferencesKey("weather_cache_location"))
            prefs.asMap()
                .filter { (key, value) -> key.name.startsWith(WEATHER_CACHE_ENTRY_PREFIX) && isWeatherEntryPrunable(value, now) }
                .forEach { (key, _) -> prefs.remove(stringPreferencesKey(key.name)) }
            prefs[weatherEntryKey(cacheKey)] = encoded
        }
        Log.d(TAG, "Weather cache stored (${jsonBody.length} bytes)")
    }

    /** Cached JSON for [cacheKey] within the fresh window (CACHE_MAX_AGE_MS), or null. */
    private suspend fun getCachedWeatherJson(cacheKey: String): String? =
        readWeatherEntry(cacheKey)
            ?.takeIf { isCacheEntryUsable(it.cachedAtMillis, weatherClockMillis(), CACHE_MAX_AGE_MS) }
            // MET bodies stop being fresh at their Expires time; they can still serve as stale fallback (#1922).
            ?.takeIf { entry -> entry.validUntilMillis?.let { weatherClockMillis() < it } ?: true }
            ?.body

    /** Cached entry for [cacheKey] at any age; the caller applies the age window (#1921). */
    private suspend fun readWeatherEntry(cacheKey: String): WeatherCacheEntry? = withContext(Dispatchers.IO) {
        try {
            weatherStore.data.first()[weatherEntryKey(cacheKey)]?.let(::decodeWeatherCacheEntry)
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed (${e.javaClass.simpleName})")
            null
        }
    }

    private fun weatherEntryKey(cacheKey: String) = stringPreferencesKey(WEATHER_CACHE_ENTRY_PREFIX + cacheKey)

    /**
     * Runs [request] and hands the open response to [read], so the body read happens inside the call.
     * Cancelling the coroutine cancels the call (#1921 N2); the client's callTimeout bounds the body read.
     */
    private suspend inline fun <T> executeWeatherCall(request: Request, read: (okhttp3.Response) -> T): T =
        awaitWeatherResponse(request).use(read)

    /**
     * Suspends until response headers arrive. Cancelling the coroutine cancels the call; a response that
     * arrives after cancellation is closed.
     */
    private suspend fun awaitWeatherResponse(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    cont.resume(response) { response.close() }
                }
            })
        }

    /** One provider body ready to parse and cache. MET fields are set only for MET Norway bodies (#1922). */
    private data class WeatherBody(
        val json: String,
        val fromFallback: Boolean = false,
        val validUntilMillis: Long? = null,
        val lastModified: String? = null,
    )

    /**
     * Open-Meteo first; MET Norway only when Open-Meteo yields no usable body (#1922). The Open-Meteo attempt is
     * capped at [OPEN_METEO_BUDGET_MS] so the fallback still fits the 10 s live budget.
     */
    private suspend fun fetchWeatherBody(
        url: String,
        label: String,
        lat: Double,
        lon: Double,
        cacheKey: String,
        toMetNoJson: (String) -> String?,
    ): WeatherBody? {
        val openMeteo = withTimeoutOrNull(OPEN_METEO_BUDGET_MS) {
            retryWithBackoff(label) {
                executeWeatherCall(Request.Builder().url(url).build()) { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    response.body?.string() ?: throw IllegalStateException("Empty forecast response")
                }
            }
        }
        if (openMeteo != null) return WeatherBody(openMeteo)
        Log.w(TAG, "$label: Open-Meteo unavailable, trying MET Norway")
        return fetchMetNorwayBody(lat, lon, cacheKey, toMetNoJson)
            ?.also { Log.i(TAG, "$label answered by MET Norway") }
    }

    /**
     * One MET Norway request for the place's cache key. Sends If-Modified-Since when a stored Last-Modified
     * exists; 304 reuses the stored body. 429 sets a back-off that skips MET until Retry-After passes. Any other
     * failure returns null (#1922).
     */
    private suspend fun fetchMetNorwayBody(
        lat: Double,
        lon: Double,
        cacheKey: String,
        toMetNoJson: (String) -> String?,
    ): WeatherBody? {
        if (weatherClockMillis() < metNoBackoffUntilMillis) {
            Log.w(TAG, "MET Norway skipped: rate-limited")
            return null
        }
        val existing = readWeatherEntry(cacheKey)
        val request = Request.Builder()
            .url(metNoRequestUrl(lat, lon))
            .header("User-Agent", METNO_USER_AGENT)
            .apply { existing?.lastModified?.let { header("If-Modified-Since", it) } }
            .build()
        return try {
            executeWeatherCall(request) { response ->
                val received = weatherClockMillis()
                // Missing or malformed Expires means "not fresh": validUntil = now (#1922).
                val expires = parseHttpDateMillis(response.header("Expires")) ?: received
                when {
                    response.code == 304 && existing != null -> WeatherBody(
                        json = existing.body,
                        fromFallback = true,
                        validUntilMillis = expires,
                        lastModified = existing.lastModified,
                    )
                    response.code == 429 -> {
                        metNoBackoffUntilMillis = received + metNoBackoffMillis(response.header("Retry-After"))
                        Log.w(TAG, "MET Norway rate-limited (429); backing off")
                        null
                    }
                    response.isSuccessful -> {
                        if (response.code == 203) Log.w(TAG, "MET Norway returned 203: endpoint deprecated")
                        val raw = response.body?.string() ?: throw IllegalStateException("Empty MET response")
                        val json = toMetNoJson(raw) ?: return@executeWeatherCall null
                        WeatherBody(
                            json = json,
                            fromFallback = true,
                            validUntilMillis = expires,
                            lastModified = response.header("Last-Modified"),
                        )
                    }
                    else -> {
                        Log.w(TAG, "MET Norway failed (HTTP ${response.code})")
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MET Norway failed (${e.javaClass.simpleName})")
            null
        }
    }

    // ── Retry with exponential backoff ─────────────────────────────────────────

    private suspend fun <T> retryWithBackoff(
        label: String,
        maxRetries: Int = RETRY_MAX_ATTEMPTS,
        body: suspend () -> T,
    ): T? {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                // Only transport failures and 5xx are transient. 4xx and non-IO errors (bad body,
                // parse failure) are not retried: retrying only adds latency (#1894, #1921).
                if (e !is java.io.IOException) break
                if (e is HttpStatusException && e.code in 400..499) break
                if (attempt < maxRetries) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt)
                    Log.w(TAG, "$label failed (attempt $attempt/$maxRetries), retrying in ${delayMs}ms", e)
                    delay(delayMs)
                }
            }
        }
        Log.w(TAG, "$label failed; giving up", lastException)
        return null
    }
}
