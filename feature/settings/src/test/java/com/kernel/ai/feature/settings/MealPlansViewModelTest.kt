package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.mealplan.FavouriteRecipeMode
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeSummary
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class MealPlansViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: MealPlanSessionRepository = mockk()
    private val recentPlansFlow = MutableStateFlow(emptyList<MealPlanSnapshot>())
    private val favouriteRecipesFlow = MutableStateFlow(emptyList<FavouriteRecipeSummary>())

    private lateinit var viewModel: MealPlansViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeRecentCompletedPlans(12) } returns recentPlansFlow
        every { repository.observeFavouriteRecipes(50) } returns favouriteRecipesFlow
        viewModel = MealPlansViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState reflects repository flows and selected tab`() = runTest {
        val states = mutableListOf<MealPlansUiState>()
        val collectJob = launch { viewModel.uiState.collect { states += it } }
        testDispatcher.scheduler.advanceUntilIdle()

        recentPlansFlow.value = listOf(planSnapshot())
        favouriteRecipesFlow.value = listOf(favouriteRecipe())
        viewModel.setTab(MealPlansBrowserTab.FAVOURITES)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = states.last()
        assertEquals(MealPlansBrowserTab.FAVOURITES, state.selectedTab)
        assertEquals(1, state.recentPlans.size)
        assertEquals(1, state.favouriteRecipes.size)

        collectJob.cancel()
    }

    @Test
    fun `toggle expansion updates ui state`() = runTest {
        val states = mutableListOf<MealPlansUiState>()
        val collectJob = launch { viewModel.uiState.collect { states += it } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.togglePlanExpanded("session-1")
        viewModel.toggleDayExpanded("session-1", 0)
        viewModel.toggleFavouriteExpanded("recipe-1")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = states.last()
        assertTrue("session-1" in state.expandedPlanIds)
        assertTrue(MealPlansViewModel.dayDetailId("session-1", 0) in state.expandedDetailIds)
        assertTrue(MealPlansViewModel.favouriteDetailId("recipe-1") in state.expandedDetailIds)

        collectJob.cancel()
    }

    @Test
    fun `toggleDayFavourite delegates with inverted favourite state`() = runTest {
        val day = planDay(isFavourite = false)
        val updated = planSnapshot(days = listOf(day.copy(isFavouriteRecipe = true)))
        coEvery { repository.setRecipeFavourite("session-1", 0, true) } returns updated

        val states = mutableListOf<MealPlansUiState>()
        val collectJob = launch { viewModel.uiState.collect { states += it } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleDayFavourite("session-1", day)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.setRecipeFavourite("session-1", 0, true) }
        assertFalse("recipe-1" in states.last().pendingRecipeKeys)

        collectJob.cancel()
    }

    @Test
    fun `removeFavourite delegates to repository`() = runTest {
        coEvery { repository.removeFavouriteRecipe("recipe-1") } just Runs
        val states = mutableListOf<MealPlansUiState>()
        val collectJob = launch { viewModel.uiState.collect { states += it } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.removeFavourite("recipe-1")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.removeFavouriteRecipe("recipe-1") }
        assertFalse("recipe-1" in states.last().pendingRecipeKeys)

        collectJob.cancel()
    }

    private fun favouriteRecipe() = FavouriteRecipeSummary(
        recipeKey = "recipe-1",
        title = "Chicken Stir Fry",
        summary = "Quick bowl",
        proteinTags = listOf("chicken"),
    )

    private fun planSnapshot(days: List<MealPlanSnapshotDay> = listOf(planDay())) = MealPlanSnapshot(
        sessionId = "session-1",
        conversationId = "conv-1",
        displayName = "Meal Plan 2026-05-19 (MP-001)",
        status = MealPlanSessionStatus.COMPLETED,
        peopleCount = 4,
        daysCount = days.size,
        dietaryRestrictions = listOf("no dietary requirements"),
        proteinPreferences = listOf("chicken"),
        favouriteRecipeMode = FavouriteRecipeMode.NONE,
        activeDayIndex = null,
        pendingGenerationKind = null,
        pendingGenerationDayIndex = null,
        planVersion = 1,
        finalSummaryWritten = true,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        completedAt = 2_000L,
        cancelledAt = null,
        days = days,
    )

    private fun planDay(isFavourite: Boolean = false) = MealPlanSnapshotDay(
        id = "day-1",
        dayIndex = 0,
        title = "Chicken Stir Fry",
        summary = "Quick bowl",
        proteinTags = listOf("chicken"),
        status = com.kernel.ai.core.memory.mealplan.MealPlanDayStatus.PERSISTED,
        currentRecipeVersion = 1,
        attemptCount = 1,
        lastErrorCode = null,
        lastErrorMessage = null,
        currentRecipe = recipeDraft(),
        recipeKey = "recipe-1",
        isFavouriteRecipe = isFavourite,
    )

    private fun recipeDraft() = RecipeDraft(
        title = "Chicken Stir Fry",
        servings = 4,
        ingredients = listOf(
            RecipeDraftIngredient(
                originalText = "500 g chicken thigh",
                amount = "500",
                unit = "g",
                item = "chicken thigh",
                note = null,
            ),
        ),
        methodSteps = listOf(
            RecipeDraftMethodStep(stepNumber = 1, text = "Slice the vegetables."),
            RecipeDraftMethodStep(stepNumber = 2, text = "Stir-fry everything until glossy."),
        ),
    )
}
