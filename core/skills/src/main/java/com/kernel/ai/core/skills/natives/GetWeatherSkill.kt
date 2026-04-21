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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

@Singleton
class GetWeatherSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) : Skill {

    override val name = "get_weather_gps"
    override val description =
        "Get current weather or a multi-day forecast. Uses device GPS by default — only pass a " +
            "location if the user explicitly names a place or says 'at home'. " +
            "Profile location is a fallback only when GPS is unavailable. " +
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

        return try {
            if (!location.isNullOrBlank()) {
                fetchByLocationName(location, forecastDays)
            } else {
                fetchByDeviceLocation(forecastDays)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetWeatherSkill failed", e)
            SkillResult.Failure(name, "Couldn't fetch weather: ${e.message}")
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByLocationName(locationName: String, forecastDays: Int = 0): SkillResult {
        val coordinates = geocodeLocation(locationName)
            ?: return SkillResult.Failure(
                name,
                "Couldn't find location: $locationName. Please try a different city or location name.",
            )
        
        return if (forecastDays > 0) {
            fetchForecast(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = locationName,
                days = forecastDays
            )
        } else {
            fetchWeather(
                lat = coordinates.first,
                lon = coordinates.second,
                displayName = locationName
            )
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

    private suspend fun fetchByDeviceLocation(forecastDays: Int = 0): SkillResult {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return SkillResult.Failure(
                name,
                "Location permission not granted. Try asking about weather in a specific city " +
                    "using run_js with skill_name='get-weather-city'.",
            )
        }
        val loc = getLastKnownLocation()
            ?: return SkillResult.Failure(
                name,
                "Couldn't get device location. Try asking about a specific city.",
            )
        val displayName = reverseGeocode(loc.latitude, loc.longitude)
        return if (forecastDays > 0) {
            fetchForecast(lat = loc.latitude, lon = loc.longitude, displayName = displayName, days = forecastDays)
        } else {
            fetchWeather(lat = loc.latitude, lon = loc.longitude, displayName = displayName)
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

    private suspend fun fetchForecast(lat: Double, lon: Double, displayName: String?, days: Int): SkillResult =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=$days&wind_speed_unit=ms"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SkillResult.Failure(
                        name,
                        "Forecast API returned ${response.code}.",
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext SkillResult.Failure(name, "Empty forecast response.")
                parseForecastResponse(body, displayName)
            }
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
            ),
        )
    }

    private fun formatForecastDate(dateStr: String): String {
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

    // ── Air quality fetch ─────────────────────────────────────────────────────

    private data class AirQualityData(val usAqi: Int?, val pm25: Double?)

    private suspend fun fetchAirQuality(lat: Double, lon: Double): AirQualityData? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5&timezone=auto"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val current = json.optJSONObject("current") ?: return@withContext null
                    val usAqi = if (current.has("us_aqi") && !current.isNull("us_aqi"))
                        current.getInt("us_aqi") else null
                    val pm25 = if (current.has("pm2_5") && !current.isNull("pm2_5"))
                        current.getDouble("pm2_5") else null
                    AirQualityData(usAqi, pm25)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Air quality fetch failed — degrading gracefully", e)
                null
            }
        }

    // ── Weather fetch ─────────────────────────────────────────────────────────

    private suspend fun fetchWeather(lat: Double, lon: Double, displayName: String?): SkillResult =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m,precipitation_probability,precipitation,uv_index" +
                "&daily=uv_index_max,sunrise,sunset,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=1&timezone=auto&wind_speed_unit=ms"
            val weatherBody = httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SkillResult.Failure(name, "Weather API returned ${response.code}.")
                }
                response.body?.string()
                    ?: return@withContext SkillResult.Failure(name, "Empty weather response.")
            }
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
        )
    }

    private fun uvIndexLabel(uv: Double): String = when {
        uv <= 2 -> "Low"
        uv <= 5 -> "Moderate"
        uv <= 7 -> "High"
        uv <= 10 -> "Very High"
        else -> "Extreme"
    }

    private fun aqiLabel(aqi: Int): String = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for Sensitive Groups"
        aqi <= 200 -> "Unhealthy"
        else -> "Very Unhealthy"
    }

    // ── WMO code → description / emoji ───────────────────────────────────────

    private fun wmoEmoji(code: Int): String = when (code) {
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

    private fun wmoDescription(code: Int): String = when (code) {
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
}
