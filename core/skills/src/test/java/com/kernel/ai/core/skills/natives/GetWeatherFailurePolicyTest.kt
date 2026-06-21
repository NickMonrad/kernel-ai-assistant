package com.kernel.ai.core.skills.natives

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.permissions.CapabilityKey
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

class GetWeatherFailurePolicyTest {
    private val context = mockk<Context>(relaxed = true)
    private val httpClient = OkHttpClient()


    @BeforeEach
    fun setUp() {
        mockkStatic(ActivityCompat::class)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(ActivityCompat::class)
    }
    @Test
    fun `description mentions permission prompt instead of future 1164 work`() {
        val skill = createSkill()

        assertTrue(skill.description.contains("If location permission is missing, the assistant will prompt for it."))
        assertFalse(skill.description.contains("Future #1164 work will add profile/home-location fallback"))
    }

    @Test
    fun `named-location guidance remains independent of location permission messaging`() {
        val namedLocationMessage = weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)

        assertFalse(namedLocationMessage.contains("permission", ignoreCase = true))
        assertTrue(namedLocationMessage.contains("weather in Brisbane"))
    }

    @Test
    fun `current-location permission denied returns weather location capability requirement`() = runTest {
        every {
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        val skill = createSkill()

        val result = skill.execute(SkillCall(skill.name, emptyMap()))

        assertEquals(
            SkillResult.CapabilityRequired(
                capabilityKey = CapabilityKey.WeatherCurrentLocation,
                skillName = skill.name,
                contextParams = emptyMap(),
            ),
            result,
        )
    }

    @Test
    fun `named-location weather ignores denied location permission and uses named lookup policy`() = runTest {
        every {
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        val skill = createSkill(httpClientReturning("[]"))

        val result = skill.execute(SkillCall(skill.name, mapOf("location" to "Brisbane")))

        assertEquals(
            SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)),
            result,
        )
    }

    @Test
    fun `current-location unavailable keeps stale cache eligible`() {
        assertTrue(shouldUseStaleCache(WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE))
    }

    @Test
    fun `api failure keeps stale cache eligible`() {
        assertTrue(shouldUseStaleCache(WeatherLookupFailureReason.API_UNAVAILABLE))
    }

    @Test
    fun `location permission denied skips stale cache`() {
        assertFalse(shouldUseStaleCache(WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED))
    }

    private fun createSkill(client: OkHttpClient = httpClient): GetWeatherSkill {
        every { context.applicationContext } returns context
        return GetWeatherSkill(context, client)
    }

    private fun httpClientReturning(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun shouldUseStaleCache(reason: WeatherLookupFailureReason): Boolean =
        reason != WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED
}
