package com.kernel.ai.core.skills.natives

import android.content.Context
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetWeatherFailurePolicyTest {
    private val context = mockk<Context>(relaxed = true)
    private val httpClient = OkHttpClient()

    @Test
    fun `description defers profile fallback to future 1164 work`() {
        val skill = createSkill()

        assertTrue(skill.description.contains("Future #1164 work will add profile/home-location fallback"))
        assertFalse(skill.description.contains("Profile location is a fallback only when GPS is unavailable"))
    }

    @Test
    fun `named-location guidance remains independent of location permission messaging`() {
        val namedLocationMessage = weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)

        assertFalse(namedLocationMessage.contains("permission", ignoreCase = true))
        assertTrue(namedLocationMessage.contains("weather in Brisbane"))
    }

    @Test
    fun `location permission denied returns direct guidance immediately`() = runTest {
        val result = permissionDeniedFailureResult()

        val reply = result as SkillResult.DirectReply
        assertEquals(weatherFailureMessage(WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED), reply.content)
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

    private fun createSkill(): GetWeatherSkill {
        every { context.applicationContext } returns context
        return GetWeatherSkill(context, httpClient)
    }

    private fun permissionDeniedFailureResult(): SkillResult {
        return SkillResult.DirectReply(weatherFailureMessage(WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED))
    }

    private fun shouldUseStaleCache(reason: WeatherLookupFailureReason): Boolean =
        reason != WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED
}
