package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeBrowserItem
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeSummary
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MealPlansBrowserTab {
    RECENT_PLANS,
    FAVOURITES,
}

data class MealPlansUiState(
    val selectedTab: MealPlansBrowserTab = MealPlansBrowserTab.RECENT_PLANS,
    val recentPlans: List<MealPlanSnapshot> = emptyList(),
    val favouriteRecipes: List<FavouriteRecipeBrowserItem> = emptyList(),
    val expandedPlanIds: Set<String> = emptySet(),
    val expandedDetailIds: Set<String> = emptySet(),
    val pendingRecipeKeys: Set<String> = emptySet(),
)

@HiltViewModel
class MealPlansViewModel @Inject constructor(
    private val mealPlanSessionRepository: MealPlanSessionRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(MealPlansBrowserTab.RECENT_PLANS)
    private val expandedPlanIds = MutableStateFlow<Set<String>>(emptySet())
    private val expandedDetailIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingRecipeKeys = MutableStateFlow<Set<String>>(emptySet())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val recentPlans = mealPlanSessionRepository.observeRecentCompletedPlans(RECENT_PLAN_LIMIT)
    private val favouriteRecipes = combine(
        mealPlanSessionRepository.observeFavouriteRecipes(FAVOURITE_LIMIT),
        recentPlans,
    ) { favourites, plans ->
        buildFavouriteBrowserItems(favourites, plans)
    }
    private val browserState = combine(
        recentPlans,
        favouriteRecipes,
        expandedPlanIds,
        expandedDetailIds,
        pendingRecipeKeys,
    ) { plans, favourites, planIds, detailIds, pendingKeys ->
        MealPlansUiState(
            recentPlans = plans,
            favouriteRecipes = favourites,
            expandedPlanIds = planIds,
            expandedDetailIds = detailIds,
            pendingRecipeKeys = pendingKeys,
        )
    }

    val uiState: StateFlow<MealPlansUiState> = combine(selectedTab, browserState) { tab, state ->
        state.copy(selectedTab = tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealPlansUiState())

    val messages = _messages.asSharedFlow()

    fun setTab(tab: MealPlansBrowserTab) {
        selectedTab.value = tab
    }

    fun togglePlanExpanded(sessionId: String) {
        expandedPlanIds.update { ids ->
            if (sessionId in ids) ids - sessionId else ids + sessionId
        }
    }

    fun toggleDayExpanded(sessionId: String, dayIndex: Int) {
        toggleDetailExpanded(dayDetailId(sessionId, dayIndex))
    }

    fun toggleFavouriteExpanded(recipeKey: String) {
        toggleDetailExpanded(favouriteDetailId(recipeKey))
    }

    fun toggleDayFavourite(sessionId: String, day: MealPlanSnapshotDay) {
        val recipeKey = day.recipeKey ?: return
        if (!beginPending(recipeKey)) return
        viewModelScope.launch {
            try {
                mealPlanSessionRepository.setRecipeFavourite(
                    sessionId = sessionId,
                    dayIndex = day.dayIndex,
                    favourite = !day.isFavouriteRecipe,
                )
            } catch (_: Exception) {
                _messages.tryEmit("Couldn't update that favourite recipe.")
            } finally {
                pendingRecipeKeys.update { it - recipeKey }
            }
        }
    }

    fun removeFavourite(recipeKey: String) {
        if (!beginPending(recipeKey)) return
        viewModelScope.launch {
            try {
                mealPlanSessionRepository.removeFavouriteRecipe(recipeKey)
            } catch (_: Exception) {
                _messages.tryEmit("Couldn't remove that favourite recipe.")
            } finally {
                pendingRecipeKeys.update { it - recipeKey }
            }
        }
    }

    private fun toggleDetailExpanded(id: String) {
        expandedDetailIds.update { ids ->
            if (id in ids) ids - id else ids + id
        }
    }

    private fun beginPending(recipeKey: String): Boolean {
        if (recipeKey in pendingRecipeKeys.value) return false
        pendingRecipeKeys.update { it + recipeKey }
        return true
    }

    private fun buildFavouriteBrowserItems(
        favourites: List<FavouriteRecipeSummary>,
        recentPlans: List<MealPlanSnapshot>,
    ): List<FavouriteRecipeBrowserItem> {
        val recentPlansByRecipeKey = linkedMapOf<String, FavouriteRecipeBrowserItem>()
        recentPlans.forEach { snapshot ->
            snapshot.days.forEach { day ->
                val recipeKey = day.recipeKey ?: return@forEach
                if (recipeKey !in recentPlansByRecipeKey) {
                    recentPlansByRecipeKey[recipeKey] = FavouriteRecipeBrowserItem(
                        recipeKey = recipeKey,
                        title = day.currentRecipe?.title ?: day.title.orEmpty(),
                        summary = day.summary,
                        proteinTags = day.proteinTags,
                        recentPlanDisplayName = snapshot.displayName,
                        recentPlanSessionId = snapshot.sessionId,
                        recentDayIndex = day.dayIndex,
                        recipe = day.currentRecipe,
                    )
                }
            }
        }
        return favourites.map { favourite ->
            val fromRecentPlan = recentPlansByRecipeKey[favourite.recipeKey]
            FavouriteRecipeBrowserItem(
                recipeKey = favourite.recipeKey,
                title = favourite.title,
                summary = favourite.summary,
                proteinTags = favourite.proteinTags,
                recentPlanDisplayName = fromRecentPlan?.recentPlanDisplayName,
                recentPlanSessionId = fromRecentPlan?.recentPlanSessionId,
                recentDayIndex = fromRecentPlan?.recentDayIndex,
                recipe = fromRecentPlan?.recipe,
            )
        }
    }

    companion object {
        private const val RECENT_PLAN_LIMIT = 12
        private const val FAVOURITE_LIMIT = 50

        fun dayDetailId(sessionId: String, dayIndex: Int): String = "plan:$sessionId:$dayIndex"
        fun favouriteDetailId(recipeKey: String): String = "favourite:$recipeKey"
    }
}
