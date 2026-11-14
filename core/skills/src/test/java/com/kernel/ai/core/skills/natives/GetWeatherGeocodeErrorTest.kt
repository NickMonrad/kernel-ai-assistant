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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** #1894 — geocoder failures reach the user as an outage, not "not found", and are retried by policy. */
class GetWeatherGeocodeErrorTest {
    private val context = mockk<Context>(relaxed = true)

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
        val calls = AtomicInteger()
        val skill = GetWeatherSkill(context, geocoderReturning(503, calls))

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Auckland")))

        assertEquals(2, calls.get())
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)),
            result,
        )
    }

    @Test
    fun `geocoder 4xx is not retried`() = runTest {
        val calls = AtomicInteger()
        val skill = GetWeatherSkill(context, geocoderReturning(403, calls))

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Auckland")))

        assertEquals(1, calls.get())
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.GEOCODE_UNAVAILABLE)),
            result,
        )
    }

    @Test
    fun `empty geocoder result is reported as not found, not an outage`() = runTest {
        val calls = AtomicInteger()
        val skill = GetWeatherSkill(context, geocoderReturning(200, calls))

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Zzyzx")))

        assertEquals(1, calls.get())
        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)),
            result,
        )
    }

    private fun geocoderReturning(status: Int, calls: AtomicInteger): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("test")
                    .body((if (status == 200) "[]" else "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
}
