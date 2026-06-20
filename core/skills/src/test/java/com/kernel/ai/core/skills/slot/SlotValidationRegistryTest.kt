package com.kernel.ai.core.skills.slot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlotValidationRegistryTest {

    private val registry = SlotValidationRegistry()

    // ── Registry basics ─────────────────────────────────────────────────────

    @Test
    fun `registry returns null for unknown intent-slot pair`() {
        assertNull(registry.get("unknown_intent", "unknown_slot"))
        val result = registry.validate("unknown_intent", "unknown_slot", "anything")
        assertTrue(result.isValid)
    }

    @Test
    fun `registry returns validator for known intent-slot pair`() {
        assertNotNull(registry.get("set_timer", "duration_seconds"))
        assertNotNull(registry.get("set_alarm", "time"))
        assertNotNull(registry.get("set_alarm", "day"))
        assertNotNull(registry.get("get_weather", "location"))
    }

    @Test
    fun `registry supports custom registration`() {
        registry.register("my_intent", "my_slot") { _, _, value ->
            if (value == "valid") SlotValidationResult.valid()
            else SlotValidationResult.invalid("Bad value.")
        }
        assertTrue(registry.validate("my_intent", "my_slot", "valid").isValid)
        assertFalse(registry.validate("my_intent", "my_slot", "bad").isValid)
    }

    // ── TimerDurationValidator ──────────────────────────────────────────────

    @Test
    fun `timer duration accepts valid positive integers`() {
        assertTrue(registry.validate("set_timer", "duration_seconds", "300").isValid)
        assertTrue(registry.validate("set_timer", "duration_seconds", "30").isValid)
        assertTrue(registry.validate("set_timer", "duration_seconds", "3600").isValid)
        assertTrue(registry.validate("set_timer", "duration_seconds", "86400").isValid) // 24h
    }

    @Test
    fun `timer duration rejects non-numeric invalid values`() {
        assertInvalidTimer("donuts", "Sorry, I didn't understand that duration. How long should the timer be?")
        assertInvalidTimer("abc", "Sorry, I didn't understand that duration. How long should the timer be?")
        assertInvalidTimer("5 minutes", "Sorry, I didn't understand that duration. How long should the timer be?")
        // "5 minutes" should have been normalised to "300" by normalizeSlotReply before validation
    }

    @Test
    fun `timer duration rejects zero and negative values`() {
        assertInvalidTimer("0", "Sorry, I didn't understand that duration. How long should the timer be?")
        assertInvalidTimer("-1", "Sorry, I didn't understand that duration. How long should the timer be?")
        assertInvalidTimer("-300", "Sorry, I didn't understand that duration. How long should the timer be?")
    }

    @Test
    fun `timer duration rejects impossible durations beyond 24 hours`() {
        assertInvalidTimer("86401", "Timers can be at most 24 hours. How long should the timer be?")
        assertInvalidTimer("172800", "Timers can be at most 24 hours. How long should the timer be?")
    }

    @Test
    fun `timer duration rejects blank input`() {
        assertInvalidTimer("", "How long would you like the timer for?")
        assertInvalidTimer("   ", "How long would you like the timer for?")
    }

    // ── AlarmTimeValidator ──────────────────────────────────────────────────

    @Test
    fun `alarm time accepts valid time formats`() {
        assertTrue(registry.validate("set_alarm", "time", "5pm").isValid)
        assertTrue(registry.validate("set_alarm", "time", "7:30").isValid)
        assertTrue(registry.validate("set_alarm", "time", "14:00").isValid)
        assertTrue(registry.validate("set_alarm", "time", "9 o'clock").isValid)
        assertTrue(registry.validate("set_alarm", "time", "6:30 am").isValid)
        assertTrue(registry.validate("set_alarm", "time", "12:00 pm").isValid)
        assertTrue(registry.validate("set_alarm", "time", "12am").isValid)
        assertTrue(registry.validate("set_alarm", "time", "7am").isValid)
        assertTrue(registry.validate("set_alarm", "time", "11:45pm").isValid)
    }

    @Test
    fun `alarm time rejects invalid time formats`() {
        assertInvalidAlarmTime("donuts")
        assertInvalidAlarmTime("abc")
        assertInvalidAlarmTime("never")
        assertInvalidAlarmTime("later")
        assertInvalidAlarmTime("25:00")
        assertInvalidAlarmTime("13:60")
    }

    @Test
    fun `alarm time rejects blank input`() {
        assertInvalidAlarmTime("")
        assertInvalidAlarmTime("   ")
    }

    // ── AlarmDayValidator ───────────────────────────────────────────────────

    @Test
    fun `alarm day accepts valid day names`() {
        assertTrue(registry.validate("set_alarm", "day", "monday").isValid)
        assertTrue(registry.validate("set_alarm", "day", "Tuesday").isValid)
        assertTrue(registry.validate("set_alarm", "day", "mon").isValid)
        assertTrue(registry.validate("set_alarm", "day", "today").isValid)
        assertTrue(registry.validate("set_alarm", "day", "tomorrow").isValid)
        assertTrue(registry.validate("set_alarm", "day", "next friday").isValid)
        assertTrue(registry.validate("set_alarm", "day", "this saturday").isValid)
    }

    @Test
    fun `alarm day is optional`() {
        // Empty day is valid — signals no specific day (alarm for tonight/tomorrow automatically)
        assertTrue(registry.validate("set_alarm", "day", "").isValid)
    }

    @Test
    fun `alarm day rejects invalid day names`() {
        assertFalse(registry.validate("set_alarm", "day", "donuts").isValid)
        assertFalse(registry.validate("set_alarm", "day", "never").isValid)
        assertFalse(registry.validate("set_alarm", "day", "next never").isValid)
    }

    // ── WeatherLocationValidator ────────────────────────────────────────────

    @Test
    fun `weather location accepts normal place names`() {
        assertTrue(registry.validate("get_weather", "location", "Auckland").isValid)
        assertTrue(registry.validate("get_weather", "location", "New York").isValid)
        assertTrue(registry.validate("get_weather", "location", "Los Angeles").isValid)
        assertTrue(registry.validate("get_weather", "location", "Tokyo").isValid)
        assertTrue(registry.validate("get_weather", "location", "12345").isValid) // zip codes pass
    }

    @Test
    fun `weather location rejects blank and noise`() {
        assertFalse(registry.validate("get_weather", "location", "").isValid)
        assertFalse(registry.validate("get_weather", "location", "?").isValid)
        assertFalse(registry.validate("get_weather", "location", ".").isValid)
    }

    // ── TimerLabelValidator (via NonEmptyStringValidator) ───────────────────

    @Test
    fun `timer label accepts non-blank values`() {
        assertTrue(registry.validate("set_timer", "label", "pasta").isValid)
        assertTrue(registry.validate("set_timer", "label", "laundry").isValid)
    }

    @Test
    fun `timer label rejects blank values`() {
        assertFalse(registry.validate("set_timer", "label", "").isValid)
        assertFalse(registry.validate("set_timer", "label", "   ").isValid)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun assertInvalidTimer(value: String) {
        val result = registry.validate("set_timer", "duration_seconds", value)
        assertFalse(result.isValid, "Expected '$value' to be invalid for duration_seconds")
    }

    private fun assertInvalidTimer(value: String, expectedMessage: String) {
        val result = registry.validate("set_timer", "duration_seconds", value)
        assertFalse(result.isValid, "Expected '$value' to be invalid for duration_seconds")
        assertEquals(expectedMessage, result.errorMessage)
    }

    private fun assertInvalidAlarmTime(value: String) {
        val result = registry.validate("set_alarm", "time", value)
        assertFalse(result.isValid, "Expected '$value' to be invalid for alarm time")
    }
}
