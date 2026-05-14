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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MealPlannerCoordinatorTest {
    private val sessionRepository = mockk<MealPlanSessionRepository>()
    private val slotExtractor = MealPlannerSlotExtractor()
    private val jsonParser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()
    private val inferenceEngine = mockk<InferenceEngine>()
    private val embeddingEngine = mockk<EmbeddingEngine>()
    private val memoryRepository = mockk<MemoryRepository>()

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
        coEvery { sessionRepository.markPendingGeneration("session-1", PendingGenerationKind.PLAN) } returns Unit
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
    fun `ingestUserMessage surfaces blank plan output cleanly`() = runTest {
        val ready = collectingSnapshot().copy(
            peopleCount = 4,
            daysCount = 2,
            dietaryRestrictions = listOf("low lactose"),
            proteinPreferences = listOf("chicken"),
        )
        val collecting = collectingSnapshot().copy(peopleCount = 4, daysCount = 2)
        coEvery { sessionRepository.getActiveSession("conv") } returns collecting
        coEvery {
            sessionRepository.updateRequiredSlots(
                sessionId = "session-1",
                peopleCount = null,
                daysCount = null,
                dietaryRestrictions = listOf("low lactose"),
                proteinPreferences = listOf("chicken"),
            )
        } returns ready
        coEvery { sessionRepository.markPendingGeneration("session-1", PendingGenerationKind.PLAN) } returns Unit
        coEvery { sessionRepository.markGenerationFailure("session-1", null, "PLAN_NO_OUTPUT", "The model did not return a plan.") } returns ready
        coEvery { inferenceEngine.generateOnce(any(), any(), false, true) } returns ""

        val reply = coordinator.ingestUserMessage("conv", "low lactose, chicken")

        assertTrue(reply.content.contains("didn't return one", ignoreCase = true))
        assertFalse(reply.content.contains("JSON object", ignoreCase = true))
    }

    @Test
    fun `regenerate day recipe uses non-thinking structured generation`() = runTest {
        val active = readyForFinalizeSnapshot(finalSummaryWritten = false).copy(
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
        )
        val failed = active.copy(
            pendingGenerationKind = PendingGenerationKind.RECIPE,
            pendingGenerationDayIndex = 0,
            activeDayIndex = 0,
        )

        coEvery { sessionRepository.getActiveSession("conv") } returns failed
        coEvery { sessionRepository.markPendingGeneration("session-1", PendingGenerationKind.RECIPE, 0) } returns Unit
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
        coEvery { memoryRepository.addEpisodicMemory("conv", "Created a 2-day meal plan.", any()) } returns "mem-1"
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
            MealPlanSnapshotDay(
                id = "day-1",
                dayIndex = 0,
                title = "Chicken stir-fry",
                summary = "Quick dinner",
                proteinTags = listOf("chicken"),
                status = MealPlanDayStatus.PERSISTED,
                currentRecipeVersion = 1,
                attemptCount = 1,
                lastErrorCode = null,
                lastErrorMessage = null,
                currentRecipe = null,
            ),
            MealPlanSnapshotDay(
                id = "day-2",
                dayIndex = 1,
                title = "Chicken curry",
                summary = "Comfort food",
                proteinTags = listOf("chicken"),
                status = MealPlanDayStatus.PERSISTED,
                currentRecipeVersion = 1,
                attemptCount = 1,
                lastErrorCode = null,
                lastErrorMessage = null,
                currentRecipe = null,
            ),
        ),
    )
}
