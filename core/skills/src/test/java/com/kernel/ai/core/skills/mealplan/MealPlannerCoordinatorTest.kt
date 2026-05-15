package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.mealplan.PendingGenerationKind
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerCoordinatorTest {
    private val sessionRepository = mockk<MealPlanSessionRepository>(relaxed = true)
    private val slotExtractor = MealPlannerSlotExtractor()
    private val jsonParser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()
    private val inferenceEngine = mockk<InferenceEngine>()
    private val embeddingEngine = mockk<EmbeddingEngine>()
    private val memoryRepository = mockk<MemoryRepository>(relaxed = true)

    private val coordinator = MealPlannerCoordinator(
        sessionRepository = sessionRepository,
        slotExtractor = slotExtractor,
        jsonParser = jsonParser,
        quantityValidator = quantityValidator,
        inferenceEngine = inferenceEngine,
        embeddingEngine = embeddingEngine,
        memoryRepository = memoryRepository,
    )

    @Test
    fun `startOrResume prompts for missing slots`() = runTest {
        coEvery { sessionRepository.startOrResume("conv") } returns collectingSnapshot()

        val reply = coordinator.startOrResume("conv")

        assertTrue(reply.content.contains("How many people"))
        assertTrue(reply.content.contains("How many days"))
    }

    @Test
    fun `collecting final slots returns plan review instead of auto generating recipes`() = runTest {
        val ready = collectingSnapshot().copy(
            peopleCount = 4,
            daysCount = 2,
            dietaryRestrictions = listOf("low lactose"),
            proteinPreferences = listOf("chicken"),
        )
        val reviewed = planReviewSnapshot()
        coEvery { sessionRepository.getActiveSession("conv") } returns collectingSnapshot().copy(peopleCount = 4, daysCount = 2)
        coEvery {
            sessionRepository.updateRequiredSlots(
                sessionId = "session-1",
                peopleCount = null,
                daysCount = null,
                dietaryRestrictions = listOf("low lactose"),
                proteinPreferences = listOf("chicken"),
            )
        } returns ready
        coEvery { inferenceEngine.generateOnce(any(), any(), false, true) } returns planJson()
        coEvery { sessionRepository.savePlanDraft("session-1", any()) } returns reviewed

        val reply = coordinator.ingestUserMessage("conv", "low lactose, chicken")

        assertTrue(reply.content.contains("generate recipes", ignoreCase = true))
        assertTrue(reply.content.contains("change preferences", ignoreCase = true))
        coVerify(exactly = 1) { sessionRepository.markPendingGeneration("session-1", PendingGenerationKind.PLAN) }
        coVerify(exactly = 0) { sessionRepository.markPendingGeneration("session-1", PendingGenerationKind.RECIPE, any()) }
    }

    @Test
    fun `plan review generate recipes emits progress and recipes sequentially`() = runTest {
        val planReview = planReviewSnapshot()
        val afterDay1 = planReview.copy(
            status = MealPlanSessionStatus.RECIPES_IN_PROGRESS,
            activeDayIndex = 1,
            pendingGenerationKind = PendingGenerationKind.RECIPE,
            pendingGenerationDayIndex = 1,
            days = listOf(
                draftDay(dayIndex = 0, title = "Lemon chicken", status = MealPlanDayStatus.PERSISTED),
                draftDay(dayIndex = 1, title = "Beef bowls", status = MealPlanDayStatus.DRAFTED),
            ),
        )
        val completed = readyForFinalizeSnapshot(finalSummaryWritten = false)
        val emitted = mutableListOf<String>()

        coEvery { sessionRepository.getActiveSession("conv") } returns planReview
        coEvery { sessionRepository.getSession("session-1") } returns planReview
        coEvery { inferenceEngine.generateOnce(any(), any(), false, true) } returnsMany listOf(recipeJson("Lemon chicken"), recipeJson("Beef bowls"))
        coEvery { sessionRepository.persistRecipeDraft("session-1", 0, any(), any(), any()) } returns afterDay1
        coEvery { sessionRepository.persistRecipeDraft("session-1", 1, any(), any(), any()) } returns completed

        val reply = coordinator.ingestUserMessage("conv", "generate recipes") { emitted += it }

        assertEquals(
            listOf(
                "Generating recipe 1 of 2…",
                expectedRecipeSection(0, "Lemon chicken"),
                "Generating recipe 2 of 2…",
                expectedRecipeSection(1, "Beef bowls"),
            ),
            emitted,
        )
        assertTrue(reply.content.contains("done meal planning", ignoreCase = true))
    }

    @Test
    fun `plan review change preferences returns to editable slot collection`() = runTest {
        val planReview = planReviewSnapshot()
        val editable = planReview.copy(status = MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS)
        coEvery { sessionRepository.getActiveSession("conv") } returns planReview
        coEvery { sessionRepository.returnToSlotCollection("session-1") } returns editable

        val reply = coordinator.ingestUserMessage("conv", "change preferences")

        assertTrue(reply.content.contains("Current plan details", ignoreCase = true))
        assertTrue(reply.content.contains("updated", ignoreCase = true))
        coVerify { sessionRepository.returnToSlotCollection("session-1") }
    }

    @Test
    fun `interrupted recipe generation requires explicit resume`() = runTest {
        val inProgress = planReviewSnapshot().copy(
            status = MealPlanSessionStatus.RECIPES_IN_PROGRESS,
            activeDayIndex = 1,
            pendingGenerationKind = PendingGenerationKind.RECIPE,
            pendingGenerationDayIndex = 1,
            days = listOf(
                draftDay(dayIndex = 0, title = "Lemon chicken", status = MealPlanDayStatus.PERSISTED),
                draftDay(dayIndex = 1, title = "Beef bowls", status = MealPlanDayStatus.DRAFTED),
            ),
        )
        coEvery { sessionRepository.getActiveSession("conv") } returns inProgress

        val reply = coordinator.ingestUserMessage("conv", "hello")

        assertTrue(reply.content.contains("generate recipes", ignoreCase = true))
        assertTrue(reply.content.contains("Day 2 of 2", ignoreCase = true))
    }

    @Test
    fun `ingestUserMessage waits while generation is already running`() = runTest {
        val ready = collectingSnapshot().copy(
            peopleCount = 4,
            daysCount = 2,
            dietaryRestrictions = listOf("low lactose"),
            proteinPreferences = listOf("chicken"),
        )
        val collecting = collectingSnapshot().copy(peopleCount = 4, daysCount = 2)
        val inFlight = ready.copy(pendingGenerationKind = PendingGenerationKind.PLAN)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coEvery { sessionRepository.getActiveSession("conv") } returnsMany listOf(collecting, inFlight)
        coEvery {
            sessionRepository.updateRequiredSlots(
                sessionId = "session-1",
                peopleCount = null,
                daysCount = null,
                dietaryRestrictions = listOf("low lactose"),
                proteinPreferences = listOf("chicken"),
            )
        } returns ready
        coEvery { sessionRepository.markGenerationFailure("session-1", null, "PLAN_NO_OUTPUT", "The model did not return a plan.") } returns ready
        coEvery { inferenceEngine.generateOnce(any(), any(), false, true) } coAnswers {
            started.complete(Unit)
            release.await()
            ""
        }

        val first = backgroundScope.async { coordinator.ingestUserMessage("conv", "low lactose, chicken") }
        started.await()

        val second = coordinator.ingestUserMessage("conv", "hello")

        assertTrue(second.content.contains("still building", ignoreCase = true))
        release.complete(Unit)
        first.await()
    }

    @Test
    fun `regenerate day recipe uses non-thinking structured generation`() = runTest {
        val failed = readyForFinalizeSnapshot(finalSummaryWritten = false).copy(
            days = readyForFinalizeSnapshot(finalSummaryWritten = false).days.map { day ->
                if (day.dayIndex == 0) {
                    day.copy(
                        status = MealPlanDayStatus.FAILED,
                        lastErrorCode = "RECIPE_NO_OUTPUT",
                        lastErrorMessage = "The model did not return a recipe.",
                    )
                } else {
                    day
                }
            },
            pendingGenerationKind = PendingGenerationKind.RECIPE,
            pendingGenerationDayIndex = 0,
            activeDayIndex = 0,
        )

        coEvery { sessionRepository.getActiveSession("conv") } returns failed
        coEvery { sessionRepository.markGenerationFailure("session-1", 0, "RECIPE_NO_OUTPUT", "The model did not return a recipe.") } returns failed
        coEvery { inferenceEngine.generateOnce(any(), any(), false, true) } returns ""

        val reply = coordinator.ingestUserMessage("conv", "regenerate day 1")

        assertTrue(reply.content.contains("didn't return a recipe", ignoreCase = true))
        coVerify(exactly = 1) { inferenceEngine.generateOnce(any(), any(), false, true) }
    }

    @Test
    fun `ingestUserMessage finalizes completed meal plan and writes summary memory`() = runTest {
        val active = readyForFinalizeSnapshot(finalSummaryWritten = false)
        val completed = active.copy(status = MealPlanSessionStatus.COMPLETED, completedAt = 1234L)
        coEvery { sessionRepository.getActiveSession("conv") } returns active
        coEvery { sessionRepository.completeSession("session-1") } returns completed
        coEvery { sessionRepository.buildFinalSummary("session-1") } returns "Created a 2-day meal plan."
        coEvery { embeddingEngine.embed("Created a 2-day meal plan.") } returns floatArrayOf(1f, 2f)
        coEvery { sessionRepository.markFinalSummaryWritten("session-1") } returns Unit

        val reply = coordinator.ingestUserMessage("conv", "done meal planning")

        assertTrue(reply.content.contains("finalized", ignoreCase = true))
        coVerify { sessionRepository.completeSession("session-1") }
        coVerify { sessionRepository.buildFinalSummary("session-1") }
        coVerify { sessionRepository.markFinalSummaryWritten("session-1") }
        coVerify { memoryRepository.addEpisodicMemory("conv", "Created a 2-day meal plan.", any()) }
    }

    private fun collectingSnapshot(): MealPlanSnapshot = MealPlanSnapshot(
        sessionId = "session-1",
        conversationId = "conv",
        status = MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS,
        peopleCount = null,
        daysCount = null,
        dietaryRestrictions = emptyList(),
        proteinPreferences = emptyList(),
        activeDayIndex = null,
        pendingGenerationKind = null,
        pendingGenerationDayIndex = null,
        planVersion = 0,
        finalSummaryWritten = false,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        cancelledAt = null,
        days = emptyList(),
    )

    private fun planReviewSnapshot(): MealPlanSnapshot = MealPlanSnapshot(
        sessionId = "session-1",
        conversationId = "conv",
        status = MealPlanSessionStatus.PLAN_REVIEW,
        peopleCount = 4,
        daysCount = 2,
        dietaryRestrictions = listOf("low lactose"),
        proteinPreferences = listOf("chicken"),
        activeDayIndex = null,
        pendingGenerationKind = null,
        pendingGenerationDayIndex = null,
        planVersion = 1,
        finalSummaryWritten = false,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        cancelledAt = null,
        days = listOf(
            draftDay(dayIndex = 0, title = "Lemon chicken", status = MealPlanDayStatus.DRAFTED),
            draftDay(dayIndex = 1, title = "Beef bowls", status = MealPlanDayStatus.DRAFTED),
        ),
    )

    private fun readyForFinalizeSnapshot(finalSummaryWritten: Boolean): MealPlanSnapshot = MealPlanSnapshot(
        sessionId = "session-1",
        conversationId = "conv",
        status = MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY,
        peopleCount = 4,
        daysCount = 2,
        dietaryRestrictions = listOf("low lactose"),
        proteinPreferences = listOf("chicken"),
        activeDayIndex = null,
        pendingGenerationKind = null,
        pendingGenerationDayIndex = null,
        planVersion = 1,
        finalSummaryWritten = finalSummaryWritten,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        cancelledAt = null,
        days = listOf(
            draftDay(dayIndex = 0, title = "Chicken stir-fry", status = MealPlanDayStatus.PERSISTED),
            draftDay(dayIndex = 1, title = "Chicken curry", status = MealPlanDayStatus.PERSISTED),
        ),
    )

    private fun draftDay(dayIndex: Int, title: String, status: MealPlanDayStatus): MealPlanSnapshotDay = MealPlanSnapshotDay(
        id = "day-${dayIndex + 1}",
        dayIndex = dayIndex,
        title = title,
        summary = "Quick dinner",
        proteinTags = listOf("chicken"),
        status = status,
        currentRecipeVersion = if (status == MealPlanDayStatus.PERSISTED) 1 else null,
        attemptCount = if (status == MealPlanDayStatus.PERSISTED) 1 else 0,
        lastErrorCode = null,
        lastErrorMessage = null,
        currentRecipe = null,
    )

    private fun planJson(): String = """
        {
          "days": [
            {"day_index": 0, "title": "Lemon chicken", "summary": "Quick dinner", "protein_tags": ["chicken"]},
            {"day_index": 1, "title": "Beef bowls", "summary": "Weeknight bowl", "protein_tags": ["beef"]}
          ]
        }
    """.trimIndent()

    private fun recipeJson(title: String): String = """
        {
          "title": "$title",
          "servings": 4,
          "ingredients": [
            "500 g chicken thigh",
            "1 tbsp olive oil"
          ],
          "method_steps": [
            "Heat the pan.",
            "Cook until done."
          ]
        }
    """.trimIndent()

    private fun expectedRecipeSection(dayIndex: Int, title: String): String = """
        Day ${dayIndex + 1}: $title
        Serves 4

        Ingredients:
        - 500 g chicken thigh
        - 1 tbsp olive oil

        Method:
        1. Heat the pan.
        2. Cook until done.
    """.trimIndent()
}
