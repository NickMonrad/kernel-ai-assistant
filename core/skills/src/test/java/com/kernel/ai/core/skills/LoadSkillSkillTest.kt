package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.skills.natives.NativeIntentHandler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoadSkillSkillTest {

    private val handler = mockk<NativeIntentHandler>(relaxed = true)
    private val runIntentSkill = RunIntentSkill(handler)
    private lateinit var registry: SkillRegistry
    private val registryLazy = object : Lazy<SkillRegistry> {
        override fun get(): SkillRegistry = registry
    }
    private val loadSkill = LoadSkillSkill(registryLazy)

    @BeforeEach
    fun setUp() {
        registry = SkillRegistry(setOf(loadSkill, runIntentSkill))
    }

    @Test
    fun `full instructions fit when the complete result envelope fits`() = runTest {
        val instructions = runIntentSkill.fullInstructions
        val budget = LoadSkillToolResultBudget.estimateTokens(mapOf("result" to instructions))

        assertEquals(SkillResult.Success(instructions), loadRunIntent(budget))
    }

    @Test
    fun `compact instructions fit when full result envelope does not fit`() = runTest {
        val full = runIntentSkill.fullInstructions
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val budget = LoadSkillToolResultBudget.estimateTokens(mapOf("result" to compact))

        assertTrue(
            LoadSkillToolResultBudget.estimateTokens(mapOf("result" to full)) > budget,
        )
        RunIntentSkill.MODEL_CALLABLE_INTENTS.forEach { intent ->
            assertTrue(intent in compact, "Compact catalogue is missing $intent")
        }
        assertTrue(RunIntentSkill.GET_DATE_DIFF_PARAMETER_HELP in compact)
        assertEquals(SkillResult.Success(compact), loadRunIntent(budget))
    }

    @Test
    fun `load skill failure envelope is returned when it fits`() = runTest {
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val budget = LoadSkillToolResultBudget.estimateTokens(mapOf("result" to compact)) - 1
        val failure = loadRunIntent(budget) as SkillResult.Failure
        val failureEnvelopeTokens =
            LoadSkillToolResultBudget.estimateTokens(mapOf("error" to failure.error))
        var aborted = false
        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillToolResultTokenBudget(budget) { aborted = true }

        assertTrue(failure.error.contains("Insufficient context"))
        assertTrue(failureEnvelopeTokens <= budget)
        assertEquals(failure.error, toolSet.loadSkill("run_intent")["error"])
        assertFalse(aborted)
        assertFalse(toolSet.loadSkillResultAbortedInCurrentAttempt())
        assertTrue(toolSet.loadSkillFailedInCurrentAttempt())
    }

    @Test
    fun `zero remaining context cancels before returning a load skill failure envelope`() = runTest {
        val toolSet = KernelAIToolSet(registryLazy)
        var cancelled = false
        toolSet.setLoadSkillToolResultTokenBudget(0) { cancelled = true }

        val returnedToolResult = toolSet.loadSkill("run_intent")

        assertTrue(returnedToolResult.isEmpty())
        assertTrue(cancelled)
        assertTrue(toolSet.loadSkillResultAbortedInCurrentAttempt())
        assertTrue(toolSet.loadSkillFailedInCurrentAttempt())
    }

    @Test
    fun `one token below the failure envelope cancels instead of returning it`() = runTest {
        val failure = loadRunIntent(0) as SkillResult.Failure
        val failureEnvelopeTokens =
            LoadSkillToolResultBudget.estimateTokens(mapOf("error" to failure.error))
        var cancelled = false
        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillToolResultTokenBudget(failureEnvelopeTokens - 1) { cancelled = true }

        val returnedToolResult = toolSet.loadSkill("run_intent")

        assertTrue(returnedToolResult.isEmpty())
        assertTrue(cancelled)
        assertTrue(toolSet.loadSkillResultAbortedInCurrentAttempt())
    }

    @Test
    fun `8000 token context keeps the full discovery instructions`() = runTest {
        val instructions = runIntentSkill.fullInstructions
        val budget = 8_000 - 2_300 - 100 - ContextWindowManager.RESPONSE_RESERVE

        assertTrue(
            LoadSkillToolResultBudget.estimateTokens(mapOf("result" to instructions)) <= budget,
        )
        assertEquals(SkillResult.Success(instructions), loadRunIntent(budget))
    }

    @Test
    fun `budgeted load skill continues into stopwatch status action`() = runTest {
        coEvery { handler.handle("get_stopwatch_status", emptyMap()) } returns
            SkillResult.DirectReply("Stopwatch is not running")
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillToolResultTokenBudget(
            LoadSkillToolResultBudget.estimateTokens(mapOf("result" to compact)),
        ) { error("A fitting load_skill result must not cancel generation") }

        assertEquals(compact, toolSet.loadSkill("run_intent")["result"])
        assertEquals(
            "Stopwatch is not running",
            toolSet.runIntent("get_stopwatch_status", "{}")["result"],
        )
        assertEquals("load_skill>run_intent", toolSet.attemptToolSequence())
    }

    @Test
    fun `load skill result budget does not alter terminal tool responses`() = runTest {
        coEvery { handler.handle("get_stopwatch_status", emptyMap()) } returns
            SkillResult.DirectReply("Stopwatch is not running")
        var cancelled = false
        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillToolResultTokenBudget(0) { cancelled = true }

        assertEquals(
            "Stopwatch is not running",
            toolSet.runIntent("get_stopwatch_status", "{}")["result"],
        )
        assertFalse(cancelled)
        assertFalse(toolSet.loadSkillResultAbortedInCurrentAttempt())
    }

    private suspend fun loadRunIntent(maxToolResultTokens: Int): SkillResult =
        loadSkill.execute(
            SkillCall(
                skillName = "load_skill",
                arguments = mapOf("skill_name" to "run_intent"),
                maxToolResultTokens = maxToolResultTokens,
            ),
        )
}
