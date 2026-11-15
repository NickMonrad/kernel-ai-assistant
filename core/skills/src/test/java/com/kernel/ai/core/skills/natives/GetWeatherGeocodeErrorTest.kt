package com.kernel.ai.core.skills.natives

import android.content.Context
import android.util.Log
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** #1894 — geocoder failures reach the user as an outage, and only geocoding is sent to the provider. */
class GetWeatherGeocodeErrorTest {
    private val context = mockk<Context>(relaxed = true)
    private val urls = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(ActivityCompat::class)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { context.applicationContext } returns context
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(ActivityCompat::class)
    }

    @Test
    fun `geocoder 503 is retried once and reported as a service outage`() = runTest {
        val skill = GetWeatherSkill(context, client { url -> if (isGeocode(url)) (503 to "") else (200 to "[]") })

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Auckland")))

        assertEquals(2, urls.count { isGeocode(it) })
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)),
            result,
        )
        assertOnlyGeocodeRequests()
    }

    @Test
    fun `geocoder 4xx is not retried`() = runTest {
        val skill = GetWeatherSkill(context, client { url -> if (isGeocode(url)) (403 to "") else (200 to "[]") })

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Auckland")))

        assertEquals(1, urls.count { isGeocode(it) })
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)),
            result,
        )
        assertOnlyGeocodeRequests()
    }

    @Test
    fun `empty geocoder result is reported as not found, not an outage`() = runTest {
        val skill = GetWeatherSkill(context, client { _ -> 200 to "[]" })

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Zzyzx")))

        assertEquals(1, urls.size)
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)),
            result,
        )
    }

    @Test
    fun `successful geocode sends no coordinates and never calls reverse geocoding`() = runTest {
        val geocodeJson = """[{"lat":"-36.85","lon":"174.76","importance":0.71,"address":{"country_code":"nz"}}]"""
        val skill = GetWeatherSkill(
            context,
            client { url -> if (isGeocode(url)) (200 to geocodeJson) else (503 to "") },
        )

        skill.execute(SkillCall(skill.name, mapOf("location" to "Auckland")))

        assertTrue(urls.any { isGeocode(it) })
        assertTrue(urls.any { it.contains("api.open-meteo.com") })
        assertFalse(urls.any { it.contains("/reverse") })
        // Forecast requests carry the geocoded point only, never the device location.
        assertOnlyGeocodeRequests()
    }

    private fun isGeocode(url: String): Boolean =
        url.startsWith("https://nominatim.openstreetmap.org/search?") && !url.contains("lat=") && !url.contains("lon=")

    /** Every outbound URL must be a named-place geocode or an Open-Meteo forecast; no reverse lookup. */
    private fun assertOnlyGeocodeRequests() {
        for (url in urls) {
            assertTrue(
                isGeocode(url) || url.contains("api.open-meteo.com") || url.contains("air-quality-api.open-meteo.com"),
                "unexpected request: $url",
            )
            assertFalse(url.contains("/reverse"), "device-location reverse lookup must not run: $url")
        }
    }

    private fun client(respond: (String) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                urls.add(url)
                val (status, body) = respond(url)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
}
