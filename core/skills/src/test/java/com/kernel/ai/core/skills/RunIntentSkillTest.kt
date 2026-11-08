package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.NativeIntentHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [RunIntentSkill] dispatch guard — validates that invalid already-populated
 * slot values are rejected before reaching [NativeIntentHandler].
 */
class RunIntentSkillTest {

    private lateinit var handler: NativeIntentHandler
    private lateinit var skill: RunIntentSkill

    @BeforeEach
    fun setUp() {
        handler = mockk(relaxed = true)
        skill = RunIntentSkill(handler)
    }

    @Test
    fun `set timer zero seconds is rejected`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "0",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `set timer negative seconds is rejected`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "-5",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `set timer over 24 hours is rejected`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "90001",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `set timer 5 minutes dispatches`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Timer set")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "300",
                "label" to "pasta",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("set_timer", mapOf("duration_seconds" to "300", "label" to "pasta")) }
    }

    @Test
    fun `set timer 30 seconds dispatches`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Timer set")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "30",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("set_timer", mapOf("duration_seconds" to "30")) }
    }

    @Test
    fun `set alarm invalid time is rejected`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_alarm",
                "time" to "later",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `set alarm valid time dispatches`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Alarm set")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_alarm",
                "time" to "5pm",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("set_alarm", mapOf("time" to "5pm")) }
    }

    @Test
    fun `set alarm with valid time and day dispatches`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Alarm set")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_alarm",
                "time" to "7am",
                "day" to "monday",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("set_alarm", mapOf("time" to "7am", "day" to "monday")) }
    }

    @Test
    fun `toggle flashlight passes through without validation`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Flashlight toggled")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "toggle_flashlight_on",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("toggle_flashlight_on", emptyMap()) }
    }

    @Test
    fun `missing intent name returns failure`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf())
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        assertEquals("run_intent", failure!!.skillName)
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `empty string intent name returns failure`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf("intent_name" to ""))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `weather blank location is rejected`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "get_weather",
                "location" to "",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure for blank location, got $result" }
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `weather auckland location dispatches`() = runTest {
        coEvery { handler.handle(any(), any()) } returns SkillResult.Success("Weather data")

        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "get_weather",
                "location" to "Auckland",
            ))
        )

        val success = result as? SkillResult.Success
        assertNotNull(success) { "Expected Success, got $result" }
        coVerify { handler.handle("get_weather", mapOf("location" to "Auckland")) }
    }

    @Test
    fun `first invalid param is returned before checking others`() = runTest {
        // Both duration_seconds and label are invalid, but duration_seconds is checked first
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "0",
                "label" to "",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        // The error should be about duration_seconds (checked first in the map iteration order)
        assertNotNull(failure!!.error)
        coVerify(inverse = true) { handler.handle(any(), any()) }
    }

    @Test
    fun `run_intent skill name prefix in failure result`() = runTest {
        val result = skill.execute(
            SkillCall("run_intent", mapOf(
                "intent_name" to "set_timer",
                "duration_seconds" to "0",
            ))
        )

        val failure = result as? SkillResult.Failure
        assertNotNull(failure) { "Expected Failure, got $result" }
        assertEquals("run_intent/set_timer", failure!!.skillName)
    }

    @Test
    fun `run_intent discovery instructions fit bounded context`() {
        assertTrue(skill.fullInstructions.length <= 4500)
        assertTrue(skill.examples.size <= 4)
    }

    @Test
    fun `model callable intent catalog matches native handlers and exclusions`() {
        val callable = RunIntentSkill.MODEL_CALLABLE_INTENTS
        val callableSet = callable.toSet()
        val exclusions = RunIntentSkill.MODEL_EXCLUDED_INTENTS
        val dedicatedTools = setOf("get_weather", "get_system_info", "save_memory", "convert_currency")
        val unavailableStubs = setOf("smart_home_on", "smart_home_off")

        assertEquals(callable.size, callableSet.size, "The model action definition must not contain duplicates")
        assertEquals(dedicatedTools + unavailableStubs, exclusions.keys)
        assertEquals(NativeIntentHandler.KNOWN_INTENTS, callableSet + exclusions.keys)
        assertTrue(callableSet.intersect(exclusions.keys).isEmpty())
        assertTrue("get_date_diff" in callableSet)
        assertTrue("bulk_add_to_list" in callableSet)
        dedicatedTools.forEach {
            assertTrue(exclusions.getValue(it).startsWith("Dedicated top-level tool"))
        }
        unavailableStubs.forEach {
            assertTrue(exclusions.getValue(it).startsWith("Non-callable stub"))
        }

        val instructions = skill.fullInstructions
        callable.forEach { intent ->
            assertTrue("$intent(" in instructions.substringBefore("Excluded:"), "$intent missing from compact catalogue")
        }
        assertTrue(RunIntentSkill.GET_DATE_DIFF_PARAMETER_HELP in instructions)
        exclusions.forEach { (intent, reason) ->
            assertTrue("$intent: $reason" in instructions, "$intent exclusion missing from discovery")
        }
        assertEquals(callable, skill.schema.parameters.getValue("intent_name").enum)

        val exampleIntentPattern = Regex("intentName=\\\"([^\\\"]+)\\\"")
        val exampleIntents = skill.examples.flatMap { example ->
            exampleIntentPattern.findAll(example).map { it.groupValues[1] }.toList()
        }
        assertTrue(exampleIntents.isNotEmpty())
        assertTrue(exampleIntents.all { it in callableSet }, "Examples must not advertise excluded intents")
        assertTrue(skill.description.contains("lists/notes") && skill.description.contains("dedicated tools"))
        assertTrue(instructions.contains("never use search_memory to answer list-content requests"))
        val catalogHeader = instructions.indexOf("FLASHLIGHT:")
        assertTrue(catalogHeader >= 0)
        val discoveryPreface = instructions.substring(0, catalogHeader)
        assertTrue("bulk_add_to_list" in discoveryPreface)
        assertTrue("all items as a JSON array" in discoveryPreface)
        assertTrue("the requested list_name" in discoveryPreface)
        assertTrue(
            skill.examples.any { "bulk_add_to_list" in it && "\"items\":[" in it },
        )
        assertTrue("After successful load_skill, call run_intent once" in discoveryPreface)
        assertTrue("wait for success" in discoveryPreface)
        assertTrue("before replying" in discoveryPreface)
        assertTrue("do not stop, reload, summarize" in discoveryPreface)
        assertTrue("ask the user to repeat" in discoveryPreface)
        assertTrue("for alarms, issue only the tool call" in instructions)
        assertTrue("Alarm: use set_alarm" in instructions)
        assertTrue("Calendar: create_calendar_event" in instructions)
        assertTrue("Never claim alarm/calendar success" in instructions)
    }
}
