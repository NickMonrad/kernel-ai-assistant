package com.kernel.ai.core.skills

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `loadSkill metadata directs RunIntent continuation`() {
        val description = KernelAIToolSet::class.java
            .getMethod("loadSkill", String::class.java)
            .getAnnotation(Tool::class.java)
            .description

        val normalizedDescription = description.lowercase()
        assertTrue("first tool call" in normalizedDescription)
        assertTrue("wait for success" in normalizedDescription)
        assertTrue("must be followed by run_intent" in normalizedDescription)
        assertTrue("before any final reply" in normalizedDescription)
    }

    @Test
    fun `runIntent parameter metadata documents get_date_diff keys and format`() {
        val description = KernelAIToolSet::class.java
            .getMethod("runIntent", String::class.java, String::class.java)
            .parameterAnnotations[1]
            .filterIsInstance<ToolParam>()
            .single()
            .description

        assertTrue(RunIntentSkill.GET_DATE_DIFF_PARAMETER_HELP in description)
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
    fun `runIntent resolves unique separator variants before execution`() = runTest {
        val runIntent = mockk<Skill>()
        every { runIntent.name } returns "run_intent"
        coEvery { runIntent.execute(any()) } returns SkillResult.DirectReply("Stopwatch status")
        every { registry.get("run_intent") } returns runIntent

        val result = toolSet.runIntent("GET STOP-WATCH STATUS", "{}")
        assertEquals("Stopwatch status", result["result"])

        coVerify {
            runIntent.execute(SkillCall("run_intent", mapOf("intent_name" to "get_stopwatch_status")))
        }
    }

    @Test
    fun `intent name resolution leaves ambiguous and unknown names unchanged`() {
        val ambiguousNames = listOf("get_stopwatch_status", "get-stopwatch-status")
        val ambiguousInput = "get_stop_watch_status"

        assertEquals(
            ambiguousInput,
            KernelAIToolSet.resolveRunIntentName(ambiguousInput, ambiguousNames),
        )
        assertEquals(
            "unknown_action",
            KernelAIToolSet.resolveRunIntentName("unknown_action"),
        )
    }

    @Test
    fun `watch-status alias requires the canonical callable intent`() {
        val alias = "get_watch_status"

        assertEquals("get_stopwatch_status", KernelAIToolSet.resolveRunIntentName(alias))
        assertEquals(
            "get_stopwatch_status",
            KernelAIToolSet.resolveRunIntentName("GET-WATCH STATUS"),
        )
        assertEquals(alias, KernelAIToolSet.resolveRunIntentName(alias, listOf("get_date")))
    }

    @Test
    fun `watch-status alias does not override ambiguity or near misses`() {
        val ambiguousInput = "GET WATCH STATUS"
        assertEquals(
            ambiguousInput,
            KernelAIToolSet.resolveRunIntentName(
                ambiguousInput,
                listOf("get_watch_status", "get-watch-status", "get_stopwatch_status"),
            ),
        )

        listOf("watch_status", "get_watch", "get_watches_status", "get_watch_statuses")
            .forEach { nearMiss ->
                assertEquals(nearMiss, KernelAIToolSet.resolveRunIntentName(nearMiss))
            }
    }

    @Test
    fun `unknown names remain unchanged with watch-status alias`() {
        val unknown = "unknown_action"

        assertEquals(unknown, KernelAIToolSet.resolveRunIntentName(unknown))
    }

    @Test
    fun `unknown runIntent name still fails through skill validation`() = runTest {
        val runIntent = mockk<Skill>()
        every { runIntent.name } returns "run_intent"
        coEvery { runIntent.execute(any()) } returns SkillResult.Failure("run_intent", "Unknown intent")
        every { registry.get("run_intent") } returns runIntent

        val result = toolSet.runIntent("unknown_action", "{}")

        assertEquals("Unknown intent", result["error"])
        coVerify {
            runIntent.execute(SkillCall("run_intent", mapOf("intent_name" to "unknown_action")))
        }
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

    @Test
    fun `safe model test sandbox allows run_intent instructions and stopwatch status`() = runTest {
        val loadSkill = mockk<Skill>()
        every { loadSkill.name } returns "load_skill"
        every { loadSkill.description } returns "run_intent instructions"
        coEvery { loadSkill.execute(any()) } returns SkillResult.Success("run_intent instructions")
        every { registry.get("load_skill") } returns loadSkill

        val runIntent = mockk<Skill>()
        every { runIntent.name } returns "run_intent"
        coEvery { runIntent.execute(any()) } returns SkillResult.DirectReply("Stopwatch is not running")
        every { registry.get("run_intent") } returns runIntent

        val scope = toolSet.beginSafeModelTestSandbox()
        try {
            assertEquals("run_intent instructions", toolSet.loadSkill("run_intent")["result"])
            assertEquals(
                "Stopwatch is not running",
                toolSet.runIntent("get_watch_status", "{}")["result"],
            )
            assertEquals("load_skill>run_intent", toolSet.attemptToolSequence())
            assertTrue(toolSet.terminalToolSucceeded())
            assertTrue(toolSet.terminalToolWasDirectReply())
        } finally {
            scope.close()
        }

        verify(exactly = 1) { registry.get("load_skill") }
        coVerify(exactly = 1) { loadSkill.execute(any()) }
        coVerify(exactly = 1) {
            runIntent.execute(SkillCall("run_intent", mapOf("intent_name" to "get_stopwatch_status")))
        }
        verify(exactly = 1) { registry.get("run_intent") }
    }

    @Test
    fun `safe model test sandbox denies other tools and every other action before dispatch`() = runTest {
        toolSet.beginLocalDiagnosticCapture()
        val scope = toolSet.beginSafeModelTestSandbox()
        try {
            assertEquals(
                "Blocked by safe model-test allowlist",
                toolSet.loadSkill("meal_planner")["error"],
            )
            assertEquals("""{"skill_name":"meal_planner"}""", toolSet.lastToolRequest())
            val blockedCalls = listOf(
                """{"intent_name":"add_to_list","parameters":{"item":"private test data"}}""" to
                    { toolSet.runIntent("add_to_list", """{"item":"private test data"}""") },
                """{"intent_name":"get_date_diff","parameters":{}}""" to
                    { toolSet.runIntent("get_date_diff", "{}") },
                """{"intent_name":"get_date","parameters":{}}""" to
                    { toolSet.runIntent("get_date", "{}") },
                """{"intent_name":"get_stopwatch_status","parameters":{"format":"private"}}""" to
                    { toolSet.runIntent("get_stopwatch_status", """{"format":"private"}""") },
                """{"intent_name":"get_list_items","parameters":{}}""" to
                    { toolSet.runIntent("get_list_items", "{}") },
                """{"intent_name":"start_stopwatch","parameters":{}}""" to
                    { toolSet.runIntent("start_stopwatch", "{}") },
                """{"intent_name":"bulk_add_to_list","parameters":{"item":"private test data"}}""" to
                    { toolSet.runIntent("bulk_add_to_list", """{"item":"private test data"}""") },
                """{"skill_name":"private","data":{"value":"private test data"}}""" to
                    { toolSet.runJs("""{"skill_name":"private","data":{"value":"private test data"}}""") },
                """{"amount":"10","from_currency":"USD","to_currency":"NZD"}""" to
                    { toolSet.convertCurrency("10", "USD", "NZD") },
                """{"location":"private test location","forecast_days":"3"}""" to
                    { toolSet.getWeather("private test location", "3") },
                """{"query":"private test query"}""" to
                    { toolSet.queryWikipedia("private test query") },
                "{}" to { toolSet.getSystemInfo() },
                """{"content":"private test fact"}""" to
                    { toolSet.saveMemory("private test fact") },
                """{"query":"private test query"}""" to
                    { toolSet.searchMemory("private test query") },
            )

            blockedCalls.forEach { (expectedRequest, call) ->
                toolSet.resetTurnState()
                assertEquals("Blocked by safe model-test allowlist", call()["error"])
                assertEquals(expectedRequest, toolSet.lastToolRequest())
                assertFalse(toolSet.lastToolWasDirectReply())
                assertTrue(toolSet.terminalToolFailed())
            }

            val snapshot = toolSet.finishLocalDiagnosticCapture()
            assertEquals(
                listOf(
                    "load_skill",
                    "run_intent",
                    "run_intent",
                    "run_intent",
                    "run_intent",
                    "run_intent",
                    "run_intent",
                    "run_intent",
                    "run_js",
                    "convert_currency",
                    "get_weather",
                    "query_wikipedia",
                    "get_system_info",
                    "save_memory",
                    "search_memory",
                ),
                snapshot.calls.map { it.name },
            )
            assertEquals((0..14).toList(), snapshot.calls.map { it.order })
            assertTrue(
                snapshot.calls.all {
                    it.resultType == "Blocked" &&
                        it.succeeded == false &&
                        it.directReply == false &&
                        it.returnedToGemma == true
                },
            )
            assertEquals("private test query", snapshot.calls[11].arguments.get("query"))
            assertEquals("private test query", snapshot.calls[14].arguments.get("query"))
        } finally {
            scope.close()
            toolSet.finishLocalDiagnosticCapture()
        }

        verify(exactly = 0) { registry.get(any()) }

        val systemInfo = mockk<Skill>()
        every { systemInfo.name } returns "get_system_info"
        coEvery { systemInfo.execute(any()) } returns SkillResult.DirectReply("System status")
        every { registry.get("get_system_info") } returns systemInfo

        assertEquals("System status", toolSet.getSystemInfo()["result"])
        verify(exactly = 1) { registry.get("get_system_info") }
    }

    // -------------------------------------------------------------------------
    // Per-attempt / per-turn tracking tests
    // -------------------------------------------------------------------------

    @Test
    fun `direct executable tool is terminal`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")

        assertTrue(toolSet.terminalToolCalledInCurrentAttempt())
        assertFalse(toolSet.loadSkillCalledInCurrentAttempt())
        assertEquals("run_intent", toolSet.terminalToolName())
    }

    @Test
    fun `load_skill alone is non-terminal`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "load_skill"
        every { skill.description } returns "instructions"
        coEvery { skill.execute(any()) } returns SkillResult.Success("instructions")
        every { registry.get("load_skill") } returns skill

        toolSet.loadSkill("run_intent")

        assertTrue(toolSet.loadSkillCalledInCurrentAttempt())
        assertFalse(toolSet.terminalToolCalledInCurrentAttempt())
        assertNull(toolSet.terminalToolName())
    }

    @Test
    fun `load_skill followed by run_intent records sequence and selects terminal`() {
        val loadSkill = mockk<Skill>()
        every { loadSkill.name } returns "load_skill"
        every { loadSkill.description } returns "instructions"
        coEvery { loadSkill.execute(any()) } returns SkillResult.Success("instructions")
        every { registry.get("load_skill") } returns loadSkill

        val runIntent = mockk<Skill>()
        every { runIntent.name } returns "run_intent"
        coEvery { runIntent.execute(any()) } returns SkillResult.DirectReply("Alarm set")
        every { registry.get("run_intent") } returns runIntent

        toolSet.loadSkill("run_intent")
        toolSet.runIntent("set_alarm", """{"hour":"7"}""")

        assertTrue(toolSet.loadSkillCalledInCurrentAttempt())
        assertTrue(toolSet.terminalToolCalledInCurrentAttempt())
        assertEquals("run_intent", toolSet.terminalToolName())
        assertEquals("load_skill>run_intent", toolSet.attemptToolSequence())
        assertEquals("load_skill>run_intent", toolSet.turnToolSequence())
    }

    @Test
    fun `executable tool returning Failure is still terminal`() {
        val skill = mockk<Skill>(relaxed = true)
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.Failure("run_intent", "Permission denied")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")

        assertTrue(toolSet.terminalToolCalledInCurrentAttempt())
        assertEquals("run_intent", toolSet.terminalToolName())
        assertTrue(toolSet.terminalToolResult()?.contains("Permission denied") == true)
    }

    @Test
    fun `resetAttemptState clears attempt calls but preserves turn sequence and terminal record`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")
        assertEquals("run_intent", toolSet.attemptToolSequence())
        assertEquals("run_intent", toolSet.turnToolSequence())

        toolSet.resetAttemptState()

        assertFalse(toolSet.loadSkillCalledInCurrentAttempt())
        assertFalse(toolSet.terminalToolCalledInCurrentAttempt())
        assertNull(toolSet.lastToolName())
        // Terminal metadata is preserved across resetAttemptState
        assertEquals("run_intent", toolSet.terminalToolName())
        assertTrue(toolSet.terminalToolSucceeded())
        assertFalse(toolSet.terminalToolFailed())
        assertEquals("run_intent", toolSet.turnToolSequence())
    }

    @Test
    fun `resetTurnState clears both attempt and turn state`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("ok")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")
        assertTrue(toolSet.terminalToolSucceeded())

        toolSet.resetTurnState()

        assertFalse(toolSet.wasToolCalled())
        assertFalse(toolSet.loadSkillCalledInCurrentAttempt())
        assertNull(toolSet.lastToolName())
        assertNull(toolSet.terminalToolName())
        assertFalse(toolSet.terminalToolSucceeded())
        assertFalse(toolSet.terminalToolFailed())
        assertEquals("none", toolSet.attemptToolSequence())
        assertEquals("none", toolSet.turnToolSequence())
    }

    @Test
    fun `DirectReply metadata associates with executable tool`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Alarm set for 7 AM")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")

        assertTrue(toolSet.terminalToolWasDirectReply())
        assertEquals("run_intent", toolSet.terminalToolName())
        assertEquals("Alarm set for 7 AM", toolSet.terminalToolResult())
    }

    @Test
    fun `load_skill never populates terminal metadata`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "load_skill"
        every { skill.description } returns "instructions"
        coEvery { skill.execute(any()) } returns SkillResult.Success("instructions")
        every { registry.get("load_skill") } returns skill

        toolSet.loadSkill("run_intent")

        assertTrue(toolSet.loadSkillCalledInCurrentAttempt())
        assertFalse(toolSet.terminalToolCalledInCurrentAttempt())
        assertNull(toolSet.terminalToolName())
        assertNull(toolSet.terminalToolResult())
        assertFalse(toolSet.terminalToolSucceeded())
        assertFalse(toolSet.terminalToolFailed())
    }

    @Test
    fun `terminalToolSucceeded and terminalToolFailed reflect preserved outcome across resetAttemptState`() {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns SkillResult.Failure("run_intent", "Permission denied")
        every { registry.get("run_intent") } returns skill

        toolSet.runIntent("set_alarm", """{"hour":"7"}""")
        assertTrue(toolSet.terminalToolFailed())
        assertFalse(toolSet.terminalToolSucceeded())

        toolSet.resetAttemptState()

        // Outcome preserved across resetAttemptState
        assertTrue(toolSet.terminalToolFailed())
        assertFalse(toolSet.terminalToolSucceeded())
        assertEquals("run_intent", toolSet.terminalToolName())

        toolSet.resetTurnState()

        // Cleared by resetTurnState
        assertFalse(toolSet.terminalToolFailed())
        assertFalse(toolSet.terminalToolSucceeded())
        assertNull(toolSet.terminalToolName())
    }


    @Test
    fun `local diagnostic capture preserves ordered calls and generation attempts`() = runTest {
        val instructions = "full skill instructions " + "i".repeat(500)
        val loadSkill = mockk<Skill>()
        every { loadSkill.name } returns "load_skill"
        every { loadSkill.description } returns instructions
        coEvery { loadSkill.execute(any()) } returns SkillResult.Success(instructions)
        every { registry.get("load_skill") } returns loadSkill

        val query = "long query " + "q".repeat(500)
        val response = "full wikipedia response " + "r".repeat(700)
        val wikipedia = mockk<Skill>()
        every { wikipedia.name } returns "query_wikipedia"
        coEvery { wikipedia.execute(any()) } returns SkillResult.DirectReply(response)
        every { registry.get("query_wikipedia") } returns wikipedia

        toolSet.queryWikipedia(query)
        toolSet.recordLocalGenerationAttempt(StringBuilder("unarmed output"), StringBuilder("unarmed thinking"))
        val unarmedSnapshot = toolSet.finishLocalDiagnosticCapture()
        assertTrue(unarmedSnapshot.calls.isEmpty())
        assertTrue(unarmedSnapshot.generationAttempts.isEmpty())

        toolSet.beginLocalDiagnosticCapture()
        toolSet.recordLocalGenerationAttempt(StringBuilder("first output"), StringBuilder("first thinking"))
        toolSet.recordLocalGenerationAttempt(StringBuilder("second output"), StringBuilder("second thinking"))
        toolSet.loadSkill("query_wikipedia")
        toolSet.queryWikipedia(query)

        val snapshot = toolSet.finishLocalDiagnosticCapture()
        assertEquals(listOf("load_skill", "query_wikipedia"), snapshot.calls.map { it.name })
        assertEquals(listOf(0, 1), snapshot.generationAttempts.map { it.order })
        assertEquals(
            listOf("first output", "second output"),
            snapshot.generationAttempts.map { it.fullContent },
        )
        assertEquals(
            listOf("first thinking", "second thinking"),
            snapshot.generationAttempts.map { it.rawThinking },
        )
        assertEquals(0, snapshot.calls[0].order)
        assertFalse(snapshot.calls[0].terminal)
        assertEquals("query_wikipedia", snapshot.calls[0].arguments["skill_name"])
        assertEquals(instructions, snapshot.calls[0].resultContent)
        assertEquals(mapOf("result" to instructions), snapshot.calls[0].toolResult)
        assertTrue(snapshot.calls[0].returnedToGemma == true)

        val terminalCall = snapshot.terminalCall
        assertEquals(1, terminalCall?.order)
        assertEquals(query, terminalCall?.arguments?.get("query"))
        assertEquals(response, terminalCall?.resultContent)
        assertEquals(mapOf("result" to response), terminalCall?.toolResult)
        assertTrue(terminalCall?.directReply == true)
        assertFalse(terminalCall?.returnedToGemma == true)
        assertTrue(terminalCall?.succeeded == true)

        toolSet.recordLocalGenerationAttempt(StringBuilder("after finish"), StringBuilder("after finish"))
        val secondSnapshot = toolSet.finishLocalDiagnosticCapture()
        assertTrue(secondSnapshot.calls.isEmpty())
        assertNull(secondSnapshot.terminalCall)
        assertTrue(secondSnapshot.generationAttempts.isEmpty())
    }

}