package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.skills.natives.NativeIntentHandler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoadSkillSkillTest {

    private val tokenEstimator = ContextWindowManager()
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
    fun `full instructions are returned when they fit`() = runTest {
        val instructions = runIntentSkill.fullInstructions
        val budget = tokenEstimator.estimateTokens(instructions)

        val result = loadRunIntent(budget)

        assertEquals(SkillResult.Success(instructions), result)
    }

    @Test
    fun `compact instructions are returned when full instructions do not fit`() = runTest {
        val full = runIntentSkill.fullInstructions
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val budget = tokenEstimator.estimateTokens(compact)

        assertTrue(tokenEstimator.estimateTokens(full) > budget)
        RunIntentSkill.MODEL_CALLABLE_INTENTS.forEach { intent ->
            assertTrue(intent in compact, "Compact catalogue is missing $intent")
        }
        assertTrue(RunIntentSkill.GET_DATE_DIFF_PARAMETER_HELP in compact)
        val result = loadRunIntent(budget)

        assertEquals(SkillResult.Success(compact), result)
    }

    @Test
    fun `load skill refuses when neither representation fits`() = runTest {
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val budget = tokenEstimator.estimateTokens(compact) - 1

        val result = loadRunIntent(budget)
        val failure = result as? SkillResult.Failure

        assertTrue(failure != null)
        assertEquals("load_skill", failure!!.skillName)
        assertTrue(failure.error.contains("Insufficient context"))
        assertTrue(tokenEstimator.estimateTokens(failure.error) < 32)

        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillInstructionTokenBudget(budget)
        assertEquals(failure.error, toolSet.loadSkill("run_intent")["error"])
        assertTrue(toolSet.loadSkillFailedInCurrentAttempt())
    }

    @Test
    fun `8000 token context keeps the full discovery instructions`() = runTest {
        val instructions = runIntentSkill.fullInstructions
        val budget = 8_000 - 2_300 - 100 - ContextWindowManager.RESPONSE_RESERVE

        assertTrue(tokenEstimator.estimateTokens(instructions) <= budget)
        assertEquals(SkillResult.Success(instructions), loadRunIntent(budget))
    }

    @Test
    fun `budgeted load skill continues into stopwatch status action`() = runTest {
        coEvery { handler.handle("get_stopwatch_status", emptyMap()) } returns
            SkillResult.DirectReply("Stopwatch is not running")
        val compact = requireNotNull(runIntentSkill.compactInstructions)
        val toolSet = KernelAIToolSet(registryLazy)
        toolSet.setLoadSkillInstructionTokenBudget(tokenEstimator.estimateTokens(compact))

        assertEquals(compact, toolSet.loadSkill("run_intent")["result"])
        assertEquals(
            "Stopwatch is not running",
            toolSet.runIntent("get_stopwatch_status", "{}")["result"],
        )
        assertEquals("load_skill>run_intent", toolSet.attemptToolSequence())
    }

    private suspend fun loadRunIntent(maxInstructionTokens: Int): SkillResult =
        loadSkill.execute(
            SkillCall(
                skillName = "load_skill",
                arguments = mapOf("skill_name" to "run_intent"),
                maxInstructionTokens = maxInstructionTokens,
            ),
        )
}
