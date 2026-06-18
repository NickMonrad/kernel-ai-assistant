package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.ToolPresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/** Strip country/region suffix from location label for speech output. */
internal fun locationForSpeech(locationLabel: String?): String =
    locationLabel?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() } ?: "your location"

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
        "Unable to fetch weather data right now. Please try again in a moment."
}
// ── Weather cache constants ──────────────────────────────────────────────────

/** Duration (ms) for serving cached weather data without a live API call. */
private const val CACHE_MAX_AGE_MS = 2 * 60 * 60 * 1000L // 2 hours

/** Max retry attempts for transient Open-Meteo failures (timeout, 5xx). */
private const val RETRY_MAX_ATTEMPTS = 2

/** Base delay for exponential backoff between retries (ms). */
private const val RETRY_BASE_DELAY_MS = 1000L

/** DataStore key for cached weather JSON body. */
private val WEATHER_CACHE_JSON_KEY = stringPreferencesKey("weather_cache_json")

/** DataStore key for cached weather timestamp (epoch millis). */
private val WEATHER_CACHE_TIMESTAMP_KEY = longPreferencesKey("weather_cache_timestamp")

/** DataStore key for cached weather location label. */
private val WEATHER_CACHE_LOCATION_KEY = stringPreferencesKey("weather_cache_location")

/** Extension property providing the weather-specific DataStore. */
private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather_cache")

private const val TAG = "KernelAI"

/** Internal wrapper for fresh weather fetch outcomes — success or a specific failure reason. */
private sealed class LiveFetchResult {
    data class Success(val result: SkillResult) : LiveFetchResult()
    data class Failed(val reason: WeatherLookupFailureReason) : LiveFetchResult()
}

@Singleton
class GetWeatherSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) : Skill {
    private val weatherStore: DataStore<Preferences> = context.weatherDataStore

    override val name = "get_weather_gps"
    override val description =
        "Get current weather or a multi-day forecast. Uses device GPS by default — only pass a " +
            "location if the user explicitly names a place or says 'at home'. " +
            "Future #1164 work will add profile/home-location fallback and contextual permission prompt + retry. " +
            "ALWAYS call this tool for any weather question — never use weather data from memory, it is stale."
    override val examples = listOf(
        "Current location weather → get_weather_gps()",
        "GPS location 3-day forecast → get_weather_gps(forecast_days=\"3\")",
        "Weather in Brisbane → get_weather_gps(location=\"Brisbane\")",
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
        ),
        required = emptyList(),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val location = call.arguments["location"]?.trim()
        val forecastDays = call.arguments["forecast_days"]?.trim()?.toIntOrNull()?.coerceIn(1, 7) ?: 0
        val dayParam = call.arguments["day"]?.trim()?.lowercase()
        val lookupMode = weatherLookupMode(location)

        val cacheKey = when (lookupMode) {
            WeatherLookupMode.NAMED_LOCATION -> when {
                dayParam == "tomorrow" -> "loc:$location:tomorrow"
                forecastDays > 0 -> "loc:$location:forecast:$forecastDays"
                else -> "loc:$location:current"
            }
            WeatherLookupMode.DEVICE_LOCATION -> when {
                dayParam == "tomorrow" -> "gps:tomorrow"
                forecastDays > 0 -> "gps:forecast:$forecastDays"
                else -> "gps:current"
            }
        }

        val freshResult = try {
            when {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live weather fetch crashed unexpectedly for key '$cacheKey'", e)
            LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
        }

        when (freshResult) {
            is LiveFetchResult.Success -> return freshResult.result
            is LiveFetchResult.Failed -> {
                val failureReason = freshResult.reason

                if (failureReason == WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED) {
                    // TODO(#1164): Replace this interim current-location guidance with the
                    // contextual capability prompt + retry flow.
                    Log.i(TAG, "Skipping stale GPS cache because Location permission is denied")
                    Log.i(TAG, "Returning interim current-location guidance pending #1164")
                    return SkillResult.DirectReply(weatherFailureMessage(failureReason))
                }

                getRawCachedWeatherJson(cacheKey)?.let { cachedJson ->
                    Log.i(TAG, "Serving stale weather cache for key '$cacheKey' after ${failureReason.name}")
                    return try {
                        if (dayParam == "tomorrow") {
                            parseForecastDayResponse(cachedJson, displayName = null, dayIndex = 1)
                        } else if (forecastDays > 0) {
                            parseForecastResponse(cachedJson, displayName = null)
                        } else {
                            parseWeatherResponse(cachedJson, displayName = null, airQuality = null)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cache re-parse failed for key '$cacheKey'", e)
                        SkillResult.Failure(name, "Cached weather data is corrupted. Please try again.")
                    }
                }

                Log.i(TAG, "Weather cache miss for key '$cacheKey' after ${failureReason.name}")
                if (failureReason == WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE) {
                    // TODO(#1164): Replace this interim current-location guidance with the
                    // contextual capability prompt + retry flow.
                    Log.i(TAG, "Returning interim current-location guidance pending #1164")
                }
                return SkillResult.DirectReply(weatherFailureMessage(failureReason))
            }
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByLocationName(
        locationName: String,
        forecastDays: Int = 0,
        targetDay: Boolean = false,
        cacheKey: String,
    ): LiveFetchResult {
        val resolvedLocationName = resolveIndirectLocationReference(locationName) ?: locationName
        val coordinates = geocodeLocation(resolvedLocationName)
            ?: run {
                Log.i(TAG, "Named-location weather geocode failed for '$resolvedLocationName'")
                return LiveFetchResult.Failed(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)
            }

        val result = if (forecastDays > 0) {
            if (targetDay) {
                fetchForecastForDay(
                    lat = coordinates.first,
                    lon = coordinates.second,
                    displayName = resolvedLocationName,
                    days = forecastDays,
                    dayIndex = 1,
                    cacheKey = cacheKey,
                )
            } else {
                fetchForecast(
                    lat = coordinates.first,
                    lon = coordinates.second,
                    displayName = resolvedLocationName,
                    days = forecastDays,
                    cacheKey = cacheKey,
                )
            }
        } else {
            fetchWeather(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = resolvedLocationName,
                cacheKey = cacheKey,
            )
        }

        return if (result != null) {
            Log.d(TAG, "Named-location weather lookup succeeded for '$resolvedLocationName'")
            LiveFetchResult.Success(result)
        } else {
            Log.w(TAG, "Weather API unavailable after geocoding '$resolvedLocationName'")
            LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
        }
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
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val results = org.json.JSONArray(body)
                if (results.length() == 0) return@withContext null
                val first = results.getJSONObject(0)
                val capitalArray = first.optJSONArray("capital") ?: return@withContext null
                capitalArray.optString(0).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Country capital lookup failed for: $countryName", e)
            null
        }
    }

    private suspend fun geocodeLocation(locationName: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=${java.net.URLEncoder.encode(locationName, "UTF-8")}" +
                "&format=json&limit=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KernelAI/1.0 (Android)")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val jsonArray = org.json.JSONArray(body)
                if (jsonArray.length() == 0) return@withContext null
                val firstResult = jsonArray.getJSONObject(0)
                val lat = firstResult.optDouble("lat", Double.NaN)
                val lon = firstResult.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@withContext null
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed for: $locationName", e)
            null
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByDeviceLocation(forecastDays: Int = 0, targetDay: Boolean = false, cacheKey: String): LiveFetchResult {
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

        val displayName = reverseGeocode(loc.latitude, loc.longitude)
        val result = if (forecastDays > 0) {
            if (targetDay) {
                fetchForecastForDay(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    displayName = displayName,
                    days = forecastDays,
                    dayIndex = 1,
                    cacheKey = cacheKey,
                )
            } else {
                fetchForecast(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    displayName = displayName,
                    days = forecastDays,
                    cacheKey = cacheKey,
                )
            }
        } else {
            fetchWeather(
                lat = loc.latitude,
                lon = loc.longitude,
                displayName = displayName,
                cacheKey = cacheKey,
            )
        }

        return if (result != null) {
            LiveFetchResult.Success(result)
        } else {
            Log.w(TAG, "Weather API unavailable for current-location weather request")
            LiveFetchResult.Failed(WeatherLookupFailureReason.API_UNAVAILABLE)
        }
    }

    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse" +
                "?lat=$lat&lon=$lon&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KernelAI/1.0 (Android)")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val address = json.optJSONObject("address") ?: return@withContext null
                val city = address.optString("city").takeIf { it.isNotBlank() }
                    ?: address.optString("town").takeIf { it.isNotBlank() }
                    ?: address.optString("village").takeIf { it.isNotBlank() }
                    ?: address.optString("suburb").takeIf { it.isNotBlank() }
                val country = address.optString("country_code").uppercase().takeIf { it.isNotBlank() }
                when {
                    city != null && country != null -> "$city, $country"
                    city != null -> city
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed", e)
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
                "?latitude=$lat&longitude=$lon" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=$days&wind_speed_unit=ms"

            val body = retryWithBackoff("Forecast API ($displayName, $days days)") {
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Forecast API returned ${response.code}")
                    }
                    response.body?.string() ?: throw IllegalStateException("Empty forecast response")
                }
            } ?: return@withContext null

            cacheWeatherJson(cacheKey, body)
            parseForecastResponse(body, displayName)
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

        val locationLabel = displayName ?: "GPS location"
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
        Log.d(TAG, "GetWeatherSkill: fetched ${len}-day forecast for $locationLabel")
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
            text,
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
            "?latitude=$lat&longitude=$lon" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=$days&wind_speed_unit=ms"

        val body = retryWithBackoff("Forecast API ($displayName, day $dayIndex)") {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Forecast API returned ${response.code}")
                }
                response.body?.string() ?: throw IllegalStateException("Empty forecast response")
            }
        } ?: return@withContext null

        cacheWeatherJson(cacheKey, body)
        parseForecastDayResponse(body, displayName, dayIndex)
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

        val locationLabel = displayName ?: "GPS location"
        val text = "$formattedDate: $emoji $desc $highStr / $lowStr, $rainStr$uvStr$sunStr"

        Log.d(TAG, "GetWeatherSkill: fetched forecast for day $dayIndex ($formattedDate) for $locationLabel")

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
            text,
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

    private data class AirQualityData(val usAqi: Int?, val pm25: Double?)

    private suspend fun fetchAirQuality(lat: Double, lon: Double): AirQualityData? =
        withContext(Dispatchers.IO) {
            val body = retryWithBackoff("Air Quality API") {
                val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5&timezone=auto"
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Air quality API returned ${response.code}")
                    response.body?.string() ?: throw IllegalStateException("Empty air quality response")
                }
            } ?: return@withContext null
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return@withContext null
            val usAqi = if (current.has("us_aqi") && !current.isNull("us_aqi"))
                current.getInt("us_aqi") else null
            val pm25 = if (current.has("pm2_5") && !current.isNull("pm2_5"))
                current.getDouble("pm2_5") else null
            AirQualityData(usAqi, pm25)
        }

    // ── Weather fetch ─────────────────────────────────────────────────────────

    private suspend fun fetchWeather(
        lat: Double,
        lon: Double,
        displayName: String?,
        cacheKey: String,
    ): SkillResult? =
        withContext(Dispatchers.IO) {
            // Check cache first
            getCachedWeatherJson(cacheKey)?.let { cachedJson ->
                return@withContext parseWeatherResponse(cachedJson, displayName, null)
            }

            // Build URL
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m,precipitation_probability,precipitation,uv_index" +
                "&daily=uv_index_max,sunrise,sunset,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=1&timezone=auto&wind_speed_unit=ms"

            // Fetch with retry
            val weatherBody = retryWithBackoff("Weather API ($displayName)") {
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Weather API returned ${response.code}")
                    }
                    response.body?.string() ?: throw IllegalStateException("Empty weather response")
                }
            } ?: return@withContext null

            // Cache the raw JSON
            cacheWeatherJson(cacheKey, weatherBody)

            val airQuality = fetchAirQuality(lat, lon)
            parseWeatherResponse(weatherBody, displayName, airQuality)
        }

    private fun parseWeatherResponse(json: String, displayName: String?, airQuality: AirQualityData?): SkillResult {
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

        val locationLabel = displayName
            ?: "%.4f, %.4f".format(obj.optDouble("latitude"), obj.optDouble("longitude"))
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

        Log.d(TAG, "GetWeatherSkill: fetched weather for $locationLabel")
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
            text,
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

    /** Store raw Open-Meteo JSON response in DataStore with current timestamp. */
    private suspend fun cacheWeatherJson(cacheKey: String, jsonBody: String) {
        val now = System.currentTimeMillis()
        weatherStore.edit { prefs ->
            prefs[WEATHER_CACHE_JSON_KEY] = jsonBody
            prefs[WEATHER_CACHE_TIMESTAMP_KEY] = now
            prefs[WEATHER_CACHE_LOCATION_KEY] = cacheKey
        }
        Log.d(TAG, "Weather cache stored: $cacheKey (${jsonBody.length} bytes)")
    }

    /** Retrieve cached weather JSON and timestamp. Returns null if cache miss or expired. */
    private suspend fun getCachedWeatherJson(cacheKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = weatherStore.data.first()
            val storedKey: String? = prefs[WEATHER_CACHE_LOCATION_KEY]
            val timestamp: Long? = prefs[WEATHER_CACHE_TIMESTAMP_KEY]
            val json: String? = prefs[WEATHER_CACHE_JSON_KEY]

            // Verify cache is for this location and not expired
            if (storedKey != cacheKey) return@withContext null
            if (timestamp == null) return@withContext null
            if (System.currentTimeMillis() - timestamp > CACHE_MAX_AGE_MS) return@withContext null
            json
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed for key: $cacheKey", e)
            null
        }
    }
    /** Retrieve cached weather JSON regardless of TTL. Returns null if cache miss. Used for stale fallback. */
    private suspend fun getRawCachedWeatherJson(cacheKey: String): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = weatherStore.data.first()
            val storedKey: String? = prefs[WEATHER_CACHE_LOCATION_KEY]
            val json: String? = prefs[WEATHER_CACHE_JSON_KEY]

            // Verify cache is for this location (no TTL check — stale data is better than nothing)
            if (storedKey != cacheKey) return@withContext null
            json
        } catch (e: Exception) {
            Log.w(TAG, "Raw cache read failed for key: $cacheKey", e)
            null
        }
    }

    // ── Retry with exponential backoff ─────────────────────────────────────────

    /**
     * Execute an HTTP call with retry on transient failures (timeout, 5xx).
     * Retries up to [RETRY_MAX_ATTEMPTS] times with exponential backoff.
     */
    private suspend fun <T> retryWithBackoff(
        label: String,
        body: suspend () -> T,
    ): T? {
        var lastException: Exception? = null
        repeat(RETRY_MAX_ATTEMPTS + 1) { attempt ->
            try {
                return body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < RETRY_MAX_ATTEMPTS) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt) // 1s, 2s
                    Log.w(TAG, "$label failed (attempt $attempt/${RETRY_MAX_ATTEMPTS}), retrying in ${delayMs}ms", e)
                    delay(delayMs)
                }
            }
        }
        Log.w(TAG, "$label failed after $RETRY_MAX_ATTEMPTS retries", lastException)
        return null
    }
}
