package com.kernel.ai.core.skills.slot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlotValidationRegistryDispatchTest {

    private val registry = SlotValidationRegistry()

    @Test
    fun `direct invalid timer duration zero seconds is rejected`() {
        val result = registry.validateParams("set_timer", mapOf("duration_seconds" to "0"))
        assertNotNull(result)
        assertFalse(result!!.isValid)
    }

    @Test
    fun `direct invalid timer duration negative is rejected`() {
        val result = registry.validateParams("set_timer", mapOf("duration_seconds" to "-5"))
        assertNotNull(result)
        assertFalse(result!!.isValid)
    }

    @Test
    fun `direct invalid timer duration 25 hours is rejected`() {
        val result = registry.validateParams("set_timer", mapOf("duration_seconds" to "90001"))
        assertNotNull(result)
        assertFalse(result!!.isValid)
    }

    @Test
    fun `direct valid timer duration 5 minutes passes`() {
        val result = registry.validateParams("set_timer", mapOf("duration_seconds" to "300"))
        assertNull(result)
    }

    @Test
    fun `direct valid timer duration 30 seconds passes`() {
        val result = registry.validateParams("set_timer", mapOf("duration_seconds" to "30"))
        assertNull(result)
    }

    @Test
    fun `direct invalid alarm time later is rejected`() {
        val result = registry.validateParams("set_alarm", mapOf("time" to "later"))
        assertNotNull(result)
        assertFalse(result!!.isValid)
    }

    @Test
    fun `direct valid alarm time 5pm passes`() {
        val result = registry.validateParams("set_alarm", mapOf("time" to "5pm"))
        assertNull(result)
    }

    @Test
    fun `internal routing params are skipped`() {
        val result = registry.validateParams(
            "set_timer",
            mapOf("intent_name" to "set_timer", "duration_seconds" to "300")
        )
        assertNull(result)
    }

    @Test
    fun `intent with no registered validators passes`() {
        val result = registry.validateParams("unknown_intent", mapOf("some_param" to "any_value"))
        assertNull(result)
    }

    @Test
    fun `empty params passes`() {
        val result = registry.validateParams("set_timer", emptyMap())
        assertNull(result)
    }

    @Test
    fun `first invalid param is returned before checking others`() {
        val result = registry.validateParams(
            "set_timer",
            mapOf(
                "duration_seconds" to "0",
                "label" to "my timer"
            )
        )
        assertNotNull(result)
        assertFalse(result!!.isValid)
        assertTrue(
            result.errorMessage?.contains("duration", ignoreCase = true) == true ||
                result.errorMessage?.contains("timer", ignoreCase = true) == true
        )
    }

    @Test
    fun `run_intent params with intent_name does not resolve to effective intent`() {
        // validateParams validates against the given intentName.
        // The run_intent → set_timer resolution happens in ChatViewModel.validateBeforeDispatch.
        val result = registry.validateParams(
            "run_intent",
            mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "0"
            )
        )
        // No validator registered for (run_intent, duration_seconds), so passes through
        assertNull(result)
    }

    @Test
    fun `weather blank location is rejected`() {
        val result = registry.validateParams("get_weather", mapOf("location" to ""))
        assertNotNull(result)
        assertFalse(result!!.isValid)
    }

    @Test
    fun `weather auckland location passes`() {
        val result = registry.validateParams("get_weather", mapOf("location" to "Auckland"))
        assertNull(result)
    }
}
