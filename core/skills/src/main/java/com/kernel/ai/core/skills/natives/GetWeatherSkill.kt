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

    override val name = "get_weather"
    override val description =
        "Get current weather conditions for the user's location or a named city. " +
            "Use when the user asks about weather, temperature, rain, forecast, or conditions."
    private val strToken = "<|" + "\"" + "|>"

    override val examples = listOf(
        "GPS/current location: <|tool_call>call:get_weather{location:${strToken}current${strToken}}<tool_call|>",
        "Named city: <|tool_call>call:get_weather{location:${strToken}Auckland${strToken}}<tool_call|>",
    )

    override val schema = SkillSchema(
        parameters = mapOf(
            "location" to SkillParameter(
                type = "string",
                description = "City name (e.g. 'Auckland') or 'current' to use device GPS. " +
                    "Defaults to 'current' if not specified.",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val location = call.arguments["location"]?.takeIf { it.isNotBlank() } ?: "current"

        return try {
            if (location.equals("current", ignoreCase = true)) {
                fetchByDeviceLocation()
            } else {
                fetchByCity(location)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetWeatherSkill failed", e)
            SkillResult.Failure(name, "Couldn't fetch weather: ${e.message}")
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private suspend fun fetchByDeviceLocation(): SkillResult {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return SkillResult.Failure(
                name,
                "Location permission not granted. Try asking about weather in a specific city, " +
                    "e.g. 'weather in Auckland'.",
            )
        }
        val loc = getLastKnownLocation()
            ?: return SkillResult.Failure(
                name,
                "Couldn't get device location. Try asking about a specific city.",
            )
        return fetchWeather(lat = loc.latitude, lon = loc.longitude, displayName = reverseGeocode(loc.latitude, loc.longitude))
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

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private suspend fun fetchByCity(city: String): SkillResult = withContext(Dispatchers.IO) {
        val result = geocodeAndFetch(city)
        if (result != null) return@withContext result

        // Retry with just the first comma-separated component (e.g. "Murrumba Downs, QLD, Australia" → "Murrumba Downs")
        val simplified = city.substringBefore(",").trim()
        if (simplified != city) {
            val retryResult = geocodeAndFetch(simplified)
            if (retryResult != null) return@withContext retryResult
        }

        SkillResult.Failure(name, "Couldn't find location '$city'. Try a different city name.")
    }

    private suspend fun geocodeAndFetch(city: String): SkillResult? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1&language=en&format=json"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return@withContext null
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val resolvedName = place.optString("name", city)
            val country = place.optString("country_code", "")
            val displayName = if (country.isNotBlank()) "$resolvedName, $country" else resolvedName
            fetchWeather(lat = lat, lon = lon, displayName = displayName)
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

    // ── WMO code → description ────────────────────────────────────────────────

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
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
