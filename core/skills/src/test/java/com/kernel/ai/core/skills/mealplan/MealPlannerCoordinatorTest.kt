package com.kernel.ai.core.skills.mealplan

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
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
