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
        "Get current weather or a multi-day forecast using the device's GPS location. " +
            "Use this when the user asks about their current location weather or doesn't specify a city. " +
            "For weather in a named city, use run_js with skill_name='get-weather-city' instead. " +
            "ALWAYS call this tool for any weather question — never use weather data from memory, it is stale."
    override val examples = listOf(
        "Current location weather → get_weather_gps()",
        "GPS location 3-day forecast → get_weather_gps(forecast_days=\"3\")",
    )

    override val schema = SkillSchema(
        parameters = mapOf(
            "forecast_days" to SkillParameter(
                type = "integer",
                description = "Number of forecast days (1–7). Omit for current conditions only.",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val forecastDays = call.arguments["forecast_days"]?.trim()?.toIntOrNull()?.coerceIn(1, 7) ?: 0

        return try {
            fetchByDeviceLocation(forecastDays)
        } catch (e: Exception) {
            Log.e(TAG, "GetWeatherSkill failed", e)
            SkillResult.Failure(name, "Couldn't fetch weather: ${e.message}")
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
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
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
                append("$formattedDate: $emoji $desc $highStr / $lowStr, $rainStr\n")
            }
        }.trimEnd()

        Log.d(TAG, "GetWeatherSkill: fetched ${len}-day forecast for $locationLabel")
        return SkillResult.Success(text)
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

    // ── Weather fetch ─────────────────────────────────────────────────────────

    private suspend fun fetchWeather(lat: Double, lon: Double, displayName: String?): SkillResult =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m,precipitation_probability" +
                "&wind_speed_unit=ms"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SkillResult.Failure(
                        name,
                        "Weather API returned ${response.code}.",
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext SkillResult.Failure(name, "Empty weather response.")
                parseWeatherResponse(body, displayName)
            }
        }

    private fun parseWeatherResponse(json: String, displayName: String?): SkillResult {
        val obj = JSONObject(json)
        val current = obj.getJSONObject("current")

        val temp = current.optDouble("temperature_2m", Double.NaN)
        val feelsLike = current.optDouble("apparent_temperature", Double.NaN)
        val humidity = current.optInt("relative_humidity_2m", -1)
        val weatherCode = current.optInt("weather_code", -1)
        val windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
        val precipChance = current.optInt("precipitation_probability", -1)

        val locationLabel = displayName
            ?: "%.4f, %.4f".format(obj.optDouble("latitude"), obj.optDouble("longitude"))
        val description = wmoDescription(weatherCode)

        val tempStr = if (!temp.isNaN()) "%.1f°C".format(temp) else "unknown"
        val feelsStr = if (!feelsLike.isNaN()) "%.1f°C".format(feelsLike) else "unknown"
        val windStr = if (!windSpeed.isNaN()) "%.1f m/s".format(windSpeed) else "unknown"
        val humidStr = if (humidity >= 0) "$humidity%" else "unknown"
        val precipStr = if (precipChance >= 0) "$precipChance%" else "unknown"

        val text = buildString {
            append("Weather in $locationLabel: $description. ")
            append("Temperature: $tempStr (feels like $feelsStr). ")
            append("Humidity: $humidStr. ")
            append("Wind: $windStr. ")
            append("Precipitation chance: $precipStr.")
        }

        Log.d(TAG, "GetWeatherSkill: fetched weather for $locationLabel")
        return SkillResult.Success(text)
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
