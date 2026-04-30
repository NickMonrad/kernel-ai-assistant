package com.kernel.ai.core.skills

import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import dagger.Lazy
import io.mockk.every
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class SaveMealPlanStateSkillTest {

    private val repository: MealPlanSessionRepository = mockk()
    private val skillRegistry: SkillRegistry = mockk()
    private val mockPlanSkill = object : Skill {
        override val name = "meal_planner_plan"
        override val description = "Plan meals"
        override val schema = SkillSchema(emptyMap(), emptyList())
        override val examples = emptyList<String>()
        override val fullInstructions = "meal_planner_plan: Generate a high-level meal plan for the specified days and preferences.\n\nIMPORTANT: Load with load_skill first. Do NOT call run_intent. Only call saveMealPlanState after showing the plan.\n\nSESSION CONTEXT BLOCK: At the start of each turn, the system may inject a [Meal Planner Session] block.\nALWAYS read this first. It contains: status, people_count, days, dietary_restrictions, protein_preferences, high_level_plan.\nTreat the session block as ground truth. If your memory conflicts, trust the session block.\nStatus values and actions:\n  collecting_preferences  -> ask the user for people, restrictions, protein, days (Stage 1).\n  high_level_plan_ready   -> plan already generated; re-show it (context may have been lost).\n  generating_recipes      -> Stage 3 started; do not regenerate the plan.\n  completed               -> done; do not continue.\nIf status is missing or collecting_preferences, ask: \"How many people, dietary restrictions, protein preferences, and how many days?\"\n\nGENERATE THE PLAN (when preferences are available):\n  1. Show summary: \"Here's a 5-day plan for 4 vegetarians with pasta/lentil focus:\"\n  2. List all days at high-level (one line each): \"Day 1: Pasta Carbonara | Day 2: Lentil Soup\"\n  3. Keep diverse and realistic - vary cuisines and protein sources.\n  4. Reflect dietary restrictions and protein preferences from the session block.\n  5. Do NOT generate recipes or ingredients yet - just dish names.\n  6. Ask: \"Ready for the full recipes with cooking steps?\"\n\nSAVE STATE (critical):\n  After showing the plan, call the saveMealPlanState tool:\n    saveMealPlanState(\n      conversationId=\"<conv-id from session block>\",\n      status=\"high_level_plan_ready\",\n      highLevelPlan=\"{\\\"day1\\\":\\\"Pasta Carbonara\\\", \\\"day2\\\":\\\"Lentil Soup\\\"}\"\n    )\n  Use EXACT parameter names (camelCase). Do NOT use snake_case.\n  Do NOT skip this - without it, context truncation loses the plan.\n\nFORMATTING: Use METRIC / NZ units only (g, kg, ml, l, tsp, tbsp, Celsius, counts). NEVER use lb, oz, Fahrenheit."
        override suspend fun execute(call: SkillCall): SkillResult = SkillResult.Success("ok")
    }
    private val mockRecipeSkill = object : Skill {
        override val name = "meal_planner_recipe"
        override val description = "Recipe skill"
        override val schema = SkillSchema(emptyMap(), emptyList())
        override val examples = emptyList<String>()
        override val fullInstructions = "meal_planner_recipe: Generate and save recipes for the current day.\n\nIMPORTANT: Load with load_skill first. Do NOT call run_intent. Only call saveMealPlanState after generating recipes.\n\nSESSION CONTEXT BLOCK: At the start of each turn, the system may inject a [Meal Planner Session] block.\nALWAYS read this first. It contains: status, people_count, days, dietary_restrictions, protein_preferences, high_level_plan.\nTreat the session block as ground truth. If your memory conflicts, trust the session block.\nStatus values and actions:\n  collecting_preferences  -> ask the user for people, restrictions, protein, days (Stage 1).\n  high_level_plan_ready   -> plan already generated; re-show it (context may have been lost).\n  generating_recipes      -> Stage 3 started; do not regenerate the plan.\n  completed               -> done; do not continue.\nIf status is missing or collecting_preferences, ask: \"How many people, dietary restrictions, protein preferences, and how many days?\"\n\nGENERATE THE PLAN (when preferences are available):\n  1. Show summary: \"Here's a 5-day plan for 4 vegetarians with pasta/lentil focus:\"\n  2. List all days at high-level (one line each): \"Day 1: Pasta Carbonara | Day 2: Lentil Soup\"\n  3. Keep diverse and realistic - vary cuisines and protein sources.\n  4. Reflect dietary restrictions and protein preferences from the session block.\n  5. Do NOT generate recipes or ingredients yet - just dish names.\n  6. Ask: \"Ready for the full recipes with cooking steps?\"\n\nSAVE STATE (critical):\n  After showing the plan, call the saveMealPlanState tool:\n    saveMealPlanState(\n      conversationId=\"<conv-id from session block>\",\n      status=\"high_level_plan_ready\",\n      highLevelPlan=\"{\\\"day1\\\":\\\"Pasta Carbonara\\\", \\\"day2\\\":\\\"Lentil Soup\\\"}\"\n    )\n  Use EXACT parameter names (camelCase). Do NOT use snake_case.\n  Do NOT skip this - without it, context truncation loses the plan.\n\nFORMATTING: Use METRIC / NZ units only (g, kg, ml, l, tsp, tbsp, Celsius, counts). NEVER use lb, oz, Fahrenheit."
        override suspend fun execute(call: SkillCall): SkillResult = SkillResult.Success("ok")
    }

    private val lazyRegistry: dagger.Lazy<SkillRegistry> = mockk {
        every { get() } returns skillRegistry
    }
    private lateinit var skill: SaveMealPlanStateSkill


    @BeforeEach
    fun setUp() {
        coEvery { skillRegistry.get("meal_planner_plan") } returns mockPlanSkill
        coEvery { skillRegistry.get("meal_planner_recipe") } returns mockRecipeSkill
        skill = SaveMealPlanStateSkill(repository, lazyRegistry)
    }



    @Test
    fun `skill name is save_meal_plan_state`() {
        assertEquals("save_meal_plan_state", skill.name)
    }

    @Test
    fun `skill has description`() {
        assertNotNull(skill.description)
        assertEquals(true, skill.description.isNotBlank())
    }

    @Test
    fun `skill schema has conversation_id as required`() {
        assertEquals(true, skill.schema.required.contains("conversation_id"))
    }

    @Test
    fun `skill schema has all expected parameters`() {
        val paramNames = skill.schema.parameters.keys.toSet()
        assertEquals(
            setOf("conversation_id", "status", "people_count", "days", "dietary_restrictions", "protein_preferences", "high_level_plan", "current_day_index"),
            paramNames,
        )
    }

    @Test
    fun `skill schema status has valid enum values`() {
        val statusParam = skill.schema.parameters["status"]
        assertNotNull(statusParam)
        assertEquals(
            setOf("collecting_preferences", "high_level_plan_ready", "generating_recipes", "completed"),
            statusParam!!.enum?.toList()?.toSet() ?: emptySet<String>(),
        )
    }

    // ─────────────────────────────── execute: validation ──────────────────────────────

    @Test
    fun `execute returns error when conversation_id is missing`() = runTest {
        val result = skill.execute(SkillCall("save_meal_plan_state", emptyMap()))

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("conversation_id"))
    }

    @Test
    fun `execute returns error when conversation_id is blank`() = runTest {
        val result = skill.execute(SkillCall("save_meal_plan_state", mapOf("conversation_id" to "")))

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("conversation_id"))
    }

    @Test
    fun `execute returns error when status is invalid`() = runTest {
        coEvery { repository.getSession(any()) } returns null
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf("conversation_id" to "conv-1", "status" to "invalid_status"),
            ),
        )

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("Invalid status"))
    }

    @Test
    fun `execute returns error when dietary_restrictions is invalid JSON`() = runTest {
        coEvery { repository.getSession(any()) } returns null
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-1",
                    "status" to "collecting_preferences",
                    "dietary_restrictions" to "not-valid-json",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("Invalid dietary_restrictions"))
    }

    @Test
    fun `execute returns error when protein_preferences is invalid JSON`() = runTest {
        coEvery { repository.getSession(any()) } returns null
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-1",
                    "status" to "collecting_preferences",
                    "protein_preferences" to "{invalid",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("Invalid protein_preferences"))
    }

    @Test
    fun `execute returns error when high_level_plan is invalid JSON`() = runTest {
        coEvery { repository.saveHighLevelPlan(any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-1",
                    "status" to "high_level_plan_ready",
                    "high_level_plan" to "not json at all",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertEquals(true, failure.error.contains("Invalid high_level_plan"))
    }

    // ─────────────────────────────── execute: success cases ──────────────────────────────

    @Test
    fun `execute saves preferences after Stage 1`() = runTest {
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-1",
                    "status" to "collecting_preferences",
                    "people_count" to "4",
                    "days" to "5",
                    "dietary_restrictions" to """["vegetarian","gluten-free"]""",
                    "protein_preferences" to """["chicken","fish"]""",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")
        val success = result as SkillResult.Success
        assertEquals(true, success.content.contains("Meal planner state saved"))
        coVerify(exactly = 1) { repository.updateStatus("conv-1", "collecting_preferences") }
        coVerify(exactly = 1) {
            repository.updatePreferences(
                conversationId = "conv-1",
                peopleCount = 4,
                days = 5,
                dietaryRestrictionsJson = """["vegetarian","gluten-free"]""",
                proteinPreferencesJson = """["chicken","fish"]""",
            )
        }
    }

    @Test
    fun `execute saves high-level plan after Stage 2`() = runTest {
        coEvery { repository.saveHighLevelPlan(any(), any()) } answers { Unit }
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-2",
                    "status" to "high_level_plan_ready",
                    "high_level_plan" to """{"day1":"Pasta Carbonara","day2":"Lentil Soup"}""",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

        val success = result as SkillResult.Success

        assertTrue(success.content.contains("high_level_plan_ready") || success.content.contains("Plan saved"))

        coVerify(exactly = 1) {

            repository.saveHighLevelPlan(

                "conv-2",

                """{"day1":"Pasta Carbonara","day2":"Lentil Soup"}""",

            )

        }

    }


    @Test
    fun `execute advances day index after Stage 3`() = runTest {
        val existingSession = com.kernel.ai.core.memory.entity.MealPlanSessionEntity(
            conversationId = "conv-3",
            status = "generating_recipes",
            currentDayIndex = 0,
        )
        coEvery { repository.getSession("conv-3") } returns existingSession
        coEvery { repository.upsert(any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-3",
                    "status" to "generating_recipes",
                    "current_day_index" to "1",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

        val success = result as SkillResult.Success

        assertTrue(success.content.contains("Current day index: 1"))

        coVerify(exactly = 1) {

            repository.upsert(

                match {

                    it.conversationId == "conv-3" &&

                    it.currentDayIndex == 1 &&

                    it.status == "generating_recipes"

                },

            )

        }

    }

    @Test
    fun `execute marks completed when all days done`() = runTest {
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-4",
                    "status" to "completed",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

        val success = result as SkillResult.Success

        assertTrue(success.content.contains("Status: completed"))

        coVerify(exactly = 1) { repository.updateStatus("conv-4", "completed") }

    }


    @Test
    fun `execute returns success content with all saved fields`() = runTest {
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-5",
                    "status" to "collecting_preferences",
                    "people_count" to "3",
                    "days" to "4",
                    "dietary_restrictions" to """["vegan"]""",
                    "protein_preferences" to """["tofu"]""",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success)
        val success = result as SkillResult.Success
        assertEquals(true, success.content.contains("Meal planner state saved for conversation conv-5"))
        assertEquals(true, success.content.contains("Status: collecting_preferences"))
        assertEquals(true, success.content.contains("People: 3"))
        assertEquals(true, success.content.contains("Days: 4"))
        assertEquals(true, success.content.contains("Dietary:"))
        assertEquals(true, success.content.contains("Proteins:"))
    }

    @Test
    fun `execute handles partial update — only status changes`() = runTest {
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf("conversation_id" to "conv-6", "status" to "high_level_plan_ready"),
            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

        coVerify(exactly = 1) { repository.updateStatus("conv-6", "high_level_plan_ready") }

    }

    @Test
    fun `execute handles partial update — only current_day_index changes`() = runTest {
        val existingSession = com.kernel.ai.core.memory.entity.MealPlanSessionEntity(
            conversationId = "conv-7",
            status = "generating_recipes",
            currentDayIndex = 1,
        )
        coEvery { repository.getSession("conv-7") } returns existingSession
        coEvery { repository.upsert(any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf("conversation_id" to "conv-7", "status" to "generating_recipes", "current_day_index" to "2"),

            ),
        )

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

        coVerify(exactly = 1) {

            repository.upsert(

                match {

                    it.conversationId == "conv-7" &&

                    it.currentDayIndex == 2

                },

            )

        }

    }


    @Test
    fun `execute handles non-existent session gracefully for status update`() = runTest {
        coEvery { repository.getSession("conv-noexist") } returns null
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-noexist",
                    "status" to "collecting_preferences",
                    "people_count" to "2",
                ),
            ),
        )

        assertEquals(true, result is SkillResult.Success)
        coVerify(exactly = 1) { repository.updateStatus("conv-noexist", "collecting_preferences") }
    }

    @Test
    fun `execute handles non-existent session gracefully for day advance`() = runTest {
        coEvery { repository.getSession("conv-noexist") } returns null
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }

        val result = skill.execute(
            SkillCall(
                "save_meal_plan_state",
                mapOf(
                    "conversation_id" to "conv-noexist",
                    "status" to "generating_recipes",
                    "current_day_index" to "0",
                ),
            ),
        )

        // When session doesn't exist, the upsert won't happen (existing is null)

        // but the call still returns Success with a confirmation.

        assertEquals(true, result is SkillResult.Success, "Expected Success but got: $result")

    }


    @Test
    fun `fullInstructions contains all field descriptions`() {
        val instructions = skill.fullInstructions
        assertEquals(true, instructions.contains("conversation_id"))
        assertEquals(true, instructions.contains("status"))
        assertEquals(true, instructions.contains("people_count"))
        assertEquals(true, instructions.contains("days"))
        assertEquals(true, instructions.contains("dietary_restrictions"))
        assertEquals(true, instructions.contains("protein_preferences"))
        assertEquals(true, instructions.contains("high_level_plan"))
        assertEquals(true, instructions.contains("current_day_index"))
    }

    @Test
    fun `fullInstructions contains rules about calling after every stage`() {
        val instructions = skill.fullInstructions
        assertEquals(true, instructions.contains("EVERY stage transition") || instructions.contains("after each stage"))
    }

    @Test
    fun `fullInstructions mentions 0-based indexing`() {
        val instructions = skill.fullInstructions
        assertEquals(true, instructions.contains("0-based"))
    }


}