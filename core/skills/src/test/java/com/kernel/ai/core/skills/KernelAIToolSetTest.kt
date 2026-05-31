package com.kernel.ai.core.skills

import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KernelAIToolSetTest {

    private lateinit var registry: SkillRegistry
    private lateinit var toolSet: KernelAIToolSet

    @BeforeEach
    fun setUp() {
        registry = mockk()
        toolSet = KernelAIToolSet(
            object : Lazy<SkillRegistry> {
                override fun get(): SkillRegistry = registry
            },
        )
    }

    @Test
    fun `queryWikipedia delegates to query_wikipedia skill`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "query_wikipedia"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Wiki result")
        every { registry.get("query_wikipedia") } returns skill

        val result = toolSet.queryWikipedia("New Zealand")

        assertEquals("Wiki result", result["result"])
        assertTrue(toolSet.wasToolCalled())
        assertTrue(toolSet.lastToolWasDirectReply())
        assertEquals("query_wikipedia", toolSet.lastToolName())
        assertEquals("{\"query\":\"New Zealand\"}", toolSet.lastToolRequest())
    }

    @Test
    fun `getSystemInfo delegates to get_system_info skill`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "get_system_info"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Date/time: Wednesday")
        every { registry.get("get_system_info") } returns skill

        val result = toolSet.getSystemInfo()

        assertEquals("Date/time: Wednesday", result["result"])
        assertTrue(toolSet.wasToolCalled())
        assertTrue(toolSet.lastToolWasDirectReply())
        assertEquals("get_system_info", toolSet.lastToolName())
    }

    @Test
    fun `getWeather delegates to get_weather skill`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "get_weather"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Sunny, 22C")
        every { registry.get("get_weather_gps") } returns skill

        val result = toolSet.getWeather("", "0")

        assertEquals("Sunny, 22C", result["result"])
        assertTrue(toolSet.wasToolCalled())
        assertTrue(toolSet.lastToolWasDirectReply())
        assertEquals("get_weather", toolSet.lastToolName())
    }

    @Test
    fun `loadSkill description mentions intent names`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "load_skill"
        every { skill.description } returns "Loads full instructions for a complex gateway skill (meal_planner, run_js, run_intent, create_calendar_event). Call only when the required parameters or intent names for that skill are unclear."
        every { registry.get("load_skill") } returns skill

        toolSet.loadSkill("meal_planner")

        assertTrue(toolSet.wasToolCalled())
        assertEquals("load_skill", toolSet.lastToolName())
    }

    @Test
    fun `runIntent escapes blank parameters`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", "")

        assertEquals("{\"intent_name\":\"set_alarm\",\"parameters\":{}}", toolSet.lastToolRequest())
    }

    @Test
    fun `runIntent escapes non-blank parameters`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", "{\"hour\":\"7\",\"minute\":\"0\"}")

        assertEquals("{\"intent_name\":\"set_alarm\",\"parameters\":{\"hour\":\"7\",\"minute\":\"0\"}}", toolSet.lastToolRequest())
    }

    @Test
    fun `runIntent fails closed on invalid JSON parameters`() = runTest {
        val result = toolSet.runIntent("set_alarm", "not json")
        assertEquals("error", result["status"])
        assertTrue(result["error"]?.contains("Invalid parameters") == true)
    }

    @Test
    fun `runJs fails closed on invalid JSON parameters`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_js"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_js") } returns skill

        val result = toolSet.runJs("not json")

        assertEquals("ok", result["result"])
        // Should still call the skill but with empty args
        assertTrue(toolSet.wasToolCalled())
    }
}
