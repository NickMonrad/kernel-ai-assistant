package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.weather.WeatherApiKeyStore
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
    private val weatherApiKeyStore: WeatherApiKeyStore,
) : Skill {

    override val name = "get_weather"
    override val description =
        "Get current weather conditions for the user's location or a named city. " +
            "Use when the user asks about weather, temperature, rain, forecast, or conditions."
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
        val apiKey = weatherApiKeyStore.getKey()
            ?: return SkillResult.Failure(
                name,
                "Weather skill is not configured. Please add your OpenWeather API key in Settings.",
            )

        val location = call.arguments["location"]?.takeIf { it.isNotBlank() } ?: "current"

        return try {
            if (location == "current") {
                fetchByLocation(apiKey)
            } else {
                fetchByCity(apiKey, location)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetWeatherSkill failed", e)
            SkillResult.Failure(name, "Couldn't fetch weather: ${e.message}")
        }
    }

    private suspend fun fetchByLocation(apiKey: String): SkillResult {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return SkillResult.Failure(
                name,
                "Location permission not granted. Ask me about weather in a specific city instead, " +
                    "e.g. 'weather in Auckland'.",
            )
        }
        return withContext(Dispatchers.IO) {
            val loc = getLastKnownLocation()
                ?: return@withContext SkillResult.Failure(
                    name,
                    "Couldn't get device location. Try asking about a specific city.",
                )
            fetchByCoords(apiKey, loc.latitude, loc.longitude)
        }
    }

    private suspend fun getLastKnownLocation(): android.location.Location? =
        suspendCancellableCoroutine { cont ->
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            @Suppress("MissingPermission")
            fusedClient.lastLocation
                .addOnSuccessListener { loc -> cont.resumeWith(Result.success(loc)) }
                .addOnFailureListener { cont.resumeWith(Result.success(null)) }
        }

    private suspend fun fetchByCity(apiKey: String, city: String): SkillResult =
        withContext(Dispatchers.IO) {
            fetchFromApi(apiKey, "q=${Uri.encode(city)}")
        }

    private fun fetchByCoords(apiKey: String, lat: Double, lon: Double): SkillResult =
        fetchFromApi(apiKey, "lat=$lat&lon=$lon")

    private fun fetchFromApi(apiKey: String, locationParam: String): SkillResult {
        val url =
            "https://api.openweathermap.org/data/2.5/weather?$locationParam&appid=$apiKey&units=metric"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SkillResult.Failure(
                    name,
                    "Weather API returned ${response.code}. Check your API key in Settings.",
                )
            }
            val body = response.body?.string()
                ?: return SkillResult.Failure(name, "Empty response from weather API.")
            return parseWeatherResponse(body)
        }
    }

    private fun parseWeatherResponse(json: String): SkillResult {
        val obj = JSONObject(json)
        val cityName = obj.optString("name", "Unknown location")
        val main = obj.getJSONObject("main")
        val temp = main.optDouble("temp", Double.NaN)
        val feelsLike = main.optDouble("feels_like", Double.NaN)
        val humidity = main.optInt("humidity", -1)
        val weatherArr = obj.optJSONArray("weather")
        val description = weatherArr?.optJSONObject(0)?.optString("description", "") ?: ""
        val windSpeed = obj.optJSONObject("wind")?.optDouble("speed", Double.NaN) ?: Double.NaN

        val tempStr = if (!temp.isNaN()) "%.1f°C".format(temp) else "unknown"
        val feelsStr = if (!feelsLike.isNaN()) "%.1f°C".format(feelsLike) else "unknown"
        val windStr = if (!windSpeed.isNaN()) "%.1f m/s".format(windSpeed) else "unknown"
        val humidStr = if (humidity >= 0) "$humidity%" else "unknown"

        val text = buildString {
            append("Weather in $cityName: $description. ")
            append("Temperature: $tempStr (feels like $feelsStr). ")
            append("Humidity: $humidStr. Wind: $windStr.")
        }
        Log.d(TAG, "GetWeatherSkill: fetched weather for $cityName")
        return SkillResult.Success(text)
    }
}
