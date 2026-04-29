package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.MealPlannerStateMachine
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MealPlannerCoordinatorTest {

    private val repository: MealPlanSessionRepository = mockk()
    private val mockSkillRegistry: dagger.Lazy<com.kernel.ai.core.skills.SkillRegistry> = mockk()
    private lateinit var coordinator: MealPlannerCoordinator

    @BeforeEach
    fun setUp() {
        coordinator = MealPlannerCoordinator(repository, mockSkillRegistry)
    }

    // ── startOrResume ──────────────────────────────────────────────────────

    @Test
    fun `startOrResume creates new session when no existing session`() = runTest {
        coEvery { repository.getSession("conv-1") } returns null
        coEvery { repository.createOrReset("conv-1") } answers { Unit }

        val result = coordinator.startOrResume("conv-1")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("How many people"))
        assertTrue(text.content.contains("dietary restrictions"))
        coVerify(exactly = 1) { repository.createOrReset("conv-1") }
    }

    @Test
    fun `startOrResume resumes session when active session exists`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-2",
            status = "collecting_preferences",
            peopleCount = 3,
            days = 5,
        )
        coEvery { repository.getSession("conv-2") } returns session

        val result = coordinator.startOrResume("conv-2")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("Welcome back"))
    }

    @Test
    fun `startOrResume creates new session when existing is terminal`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-3",
            status = "completed",
        )
        coEvery { repository.getSession("conv-3") } returns session
        coEvery { repository.createOrReset("conv-3") } answers { Unit }

        val result = coordinator.startOrResume("conv-3")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        coVerify(exactly = 1) { repository.createOrReset("conv-3") }
    }

    // ── processMessage ─────────────────────────────────────────────────────

    @Test
    fun `processMessage returns help text when no session`() = runTest {
        coEvery { repository.getSession("conv-noexist") } returns null

        val result = coordinator.processMessage("conv-noexist", "hello")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("No active meal plan"))
    }

    @Test
    fun `processMessage in COLLECTING_PREFERENCES extracts people count`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-4",
            status = "collecting_preferences",
        )
        coEvery { repository.getSession("conv-4") } returns session
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any()) } answers { Unit }
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        every { mockSkillRegistry.get() } returns mockk()

        val result = coordinator.processMessage("conv-4", "4 people for 5 days")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        // Should report what was collected
        assertTrue(text.content.contains("People: 4") || text.content.contains("I still need"))
    }

    @Test
    fun `processMessage in PLAN_DRAFT_READY confirms and transitions to GENERATING_RECIPE`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-5",
            status = "high_level_plan_ready",
            highLevelPlanJson = """{"day1":"Pasta"}""",
        )
        coEvery { repository.getSession("conv-5") } returns session
        coEvery { repository.updatePreferences(any(), any(), any(), any(), any(), any()) } answers { Unit }
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        val mockRegistry = mockk<com.kernel.ai.core.skills.SkillRegistry>()
        coEvery { mockRegistry.get("meal_planner_plan") } returns mockk<Skill>()
        every { mockSkillRegistry.get() } returns mockRegistry

        val result = coordinator.processMessage("conv-5", "yes")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("Generating the full recipes"))
        coVerify(exactly = 1) { repository.updateStatus("conv-5", "generating_recipes") }
        coVerify(exactly = 1) { repository.updatePreferences(eq("conv-5"), any(), any(), any(), any(), eq(0)) }
    }

    @Test
    fun `processMessage in PLAN_DRAFT_READY regenerates plan on negative response`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-6",
            status = "high_level_plan_ready",
        )
        coEvery { repository.getSession("conv-6") } returns session
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        val mockRegistry = mockk<com.kernel.ai.core.skills.SkillRegistry>()
        coEvery { mockRegistry.get("meal_planner_plan") } returns mockk<Skill>()
        every { mockSkillRegistry.get() } returns mockRegistry

        val result = coordinator.processMessage("conv-6", "regenerate")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        coVerify(exactly = 1) { mockRegistry.get("meal_planner_plan") }
    }

    @Test
    fun `processMessage in GENERATING_RECIPE advances day on next`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-7",
            status = "generating_recipes",
            currentDayIndex = 0,
            days = 3,
        )
        coEvery { repository.getSession("conv-7") } returns session
        coEvery { repository.advanceDay("conv-7") } answers { Unit }
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        every { mockSkillRegistry.get() } returns mockk()

        val result = coordinator.processMessage("conv-7", "next")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("Day 2"))
        coVerify(exactly = 1) { repository.advanceDay("conv-7") }
    }

    @Test
    fun `processMessage in COMPLETED offers new plan`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-8",
            status = "completed",
        )
        coEvery { repository.getSession("conv-8") } returns session

        val result = coordinator.processMessage("conv-8", "hello")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("complete"))
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    @Test
    fun `cancel updates status and returns confirmation`() = runTest {
        coEvery { repository.updateStatus("conv-9", "cancelled") } answers { Unit }

        val result = coordinator.cancel("conv-9")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("cancelled"))
        coVerify(exactly = 1) { repository.updateStatus("conv-9", "cancelled") }
    }

    // ── hasActiveSession ───────────────────────────────────────────────────

    @Test
    fun `hasActiveSession returns true for non-terminal session`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-10",
            status = "collecting_preferences",
        )
        coEvery { repository.getSession("conv-10") } returns session

        val result = coordinator.hasActiveSession("conv-10")

        assertTrue(result)
    }

    @Test
    fun `hasActiveSession returns false for terminal session`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-11",
            status = "completed",
        )
        coEvery { repository.getSession("conv-11") } returns session

        val result = coordinator.hasActiveSession("conv-11")

        assertFalse(result)
    }

    @Test
    fun `hasActiveSession returns false when no session`() = runTest {
        coEvery { repository.getSession("conv-12") } returns null

        val result = coordinator.hasActiveSession("conv-12")

        assertFalse(result)
    }

    // ── getState ───────────────────────────────────────────────────────────

    @Test
    fun `getState returns correct state from session`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-13",
            status = "high_level_plan_ready",
        )
        coEvery { repository.getSession("conv-13") } returns session

        val result = coordinator.getState("conv-13")

        assertEquals(MealPlannerStateMachine.State.PLAN_DRAFT_READY, result)
    }

    // ── State machine transitions ──────────────────────────────────────────

    @Test
    fun `state machine COLLECTING_PREFERENCES transitions to PLAN_DRAFT_READY`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.COLLECTING_PREFERENCES
        )
        assertTrue(valid.contains(MealPlannerStateMachine.State.PLAN_DRAFT_READY))
        assertTrue(valid.contains(MealPlannerStateMachine.State.CANCELLED))
        assertEquals(2, valid.size)
    }

    @Test
    fun `state machine PLAN_DRAFT_READY transitions to GENERATING_RECIPE`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.PLAN_DRAFT_READY
        )
        assertTrue(valid.contains(MealPlannerStateMachine.State.GENERATING_RECIPE))
        assertTrue(valid.contains(MealPlannerStateMachine.State.CANCELLED))
        assertEquals(2, valid.size)
    }

    @Test
    fun `state machine GENERATING_RECIPE transitions to RECIPE_REVIEW`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.GENERATING_RECIPE
        )
        assertTrue(valid.contains(MealPlannerStateMachine.State.RECIPE_REVIEW))
        assertTrue(valid.contains(MealPlannerStateMachine.State.CANCELLED))
    }

    @Test
    fun `state machine WRITING_ARTIFACTS transitions to COMPLETED`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.WRITING_ARTIFACTS
        )
        assertTrue(valid.contains(MealPlannerStateMachine.State.COMPLETED))
        assertTrue(valid.contains(MealPlannerStateMachine.State.CANCELLED))
    }

    @Test
    fun `state machine COMPLETED has no valid transitions`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.COMPLETED
        )
        assertTrue(valid.isEmpty())
    }

    @Test
    fun `state machine CANCELLED has no valid transitions`() {
        val valid = MealPlannerStateMachine.validTransitions(
            MealPlannerStateMachine.State.CANCELLED
        )
        assertTrue(valid.isEmpty())
    }

    @Test
    fun `isTerminal returns true for COMPLETED and CANCELLED`() {
        assertTrue(MealPlannerStateMachine.isTerminal(MealPlannerStateMachine.State.COMPLETED))
        assertTrue(MealPlannerStateMachine.isTerminal(MealPlannerStateMachine.State.CANCELLED))
    }

    @Test
    fun `isTerminal returns false for non-terminal states`() {
        assertFalse(MealPlannerStateMachine.isTerminal(MealPlannerStateMachine.State.COLLECTING_PREFERENCES))
        assertFalse(MealPlannerStateMachine.isTerminal(MealPlannerStateMachine.State.PLAN_DRAFT_READY))
        assertFalse(MealPlannerStateMachine.isTerminal(MealPlannerStateMachine.State.GENERATING_RECIPE))
    }

    // ── Regex parsing ──────────────────────────────────────────────────────

    @Test
    fun `people regex extracts count with people keyword`() {
        val regex = Regex("""\b(\d+)\s*(?:people|persons|pax|folks|head)\b""", RegexOption.IGNORE_CASE)
        val match = regex.find("for 4 people")
        assertNotNull(match)
        assertEquals("4", match!!.groupValues[1])
    }

    @Test
    fun `days regex extracts count with day keyword`() {
        val regex = Regex("""(\d+)\s*(?:day|week|night)s?\b""", RegexOption.IGNORE_CASE)
        val match = regex.find("for 5 days")
        assertNotNull(match)
        assertEquals("5", match!!.groupValues[1])
    }

    @Test
    fun `restriction keywords match dietary restrictions`() {
        val keywords = setOf("vegetarian", "vegan", "gluten.?free", "dairy.?free", "halal", "kosher")
        val input = "I'm vegetarian and gluten-free"
        val matched = keywords.mapNotNull { keyword ->
            Regex(keyword, RegexOption.IGNORE_CASE).find(input)?.value
        }.distinct()
        assertTrue(matched.contains("vegetarian"))
        assertTrue(matched.contains("gluten-free"))
    }

    @Test
    fun `protein keywords match protein preferences`() {
        val keywords = setOf("chicken", "beef", "fish", "pork", "tofu", "lamb", "shrimp")
        val input = "I prefer chicken and fish"
        val matched = keywords.mapNotNull { keyword ->
            Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE).find(input)?.value?.lowercase()
        }.distinct()
        assertTrue(matched.contains("chicken"))
        assertTrue(matched.contains("fish"))
    }

    // ── Resume with existing preferences ───────────────────────────────────

    @Test
    fun `resumeSession skips to plan when all preferences exist`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-14",
            status = "collecting_preferences",
            peopleCount = 4,
            days = 5,
            dietaryRestrictionsJson = """["vegetarian"]""",
            proteinPreferencesJson = """["chicken"]""",
        )
        coEvery { repository.getSession("conv-14") } returns session
        coEvery { repository.updateStatus(any(), any()) } answers { Unit }
        val mockRegistry = mockk<com.kernel.ai.core.skills.SkillRegistry>()
        coEvery { mockRegistry.get("meal_planner_plan") } returns mockk<Skill>()
        every { mockSkillRegistry.get() } returns mockRegistry

        val result = coordinator.startOrResume("conv-14")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        coVerify(exactly = 1) { repository.updateStatus("conv-14", "high_level_plan_ready") }
    }

    @Test
    fun `resumeSession shows current state when preferences incomplete`() = runTest {
        val session = MealPlanSessionEntity(
            conversationId = "conv-15",
            status = "collecting_preferences",
            peopleCount = 4,
            days = null,
        )
        coEvery { repository.getSession("conv-15") } returns session

        val result = coordinator.startOrResume("conv-15")

        assertTrue(result is MealPlannerCoordinator.CoordinatorResult.Text)
        val text = result as MealPlannerCoordinator.CoordinatorResult.Text
        assertTrue(text.content.contains("Welcome back"))
        assertTrue(text.content.contains("4 people"))
    }
}
