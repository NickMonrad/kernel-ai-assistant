package com.kernel.ai.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.entity.ListNameEntity
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
import kotlinx.coroutines.flow.map
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
    val availableLists: List<ListNameEntity> = emptyList(),
    val recentPlansQuery: String = "",
    val favouriteRecipesQuery: String = "",
    val expandedPlanIds: Set<String> = emptySet(),
    val expandedDetailIds: Set<String> = emptySet(),
    val pendingRecipeKeys: Set<String> = emptySet(),
)

private data class BrowserChrome(
    val recentPlansQuery: String,
    val favouriteRecipesQuery: String,
    val expandedPlanIds: Set<String>,
    val expandedDetailIds: Set<String>,
    val pendingRecipeKeys: Set<String>,
)

@HiltViewModel
class MealPlansViewModel @Inject constructor(
    private val mealPlanSessionRepository: MealPlanSessionRepository,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(MealPlansBrowserTab.RECENT_PLANS)
    private val recentPlansQuery = MutableStateFlow("")
    private val favouriteRecipesQuery = MutableStateFlow("")
    private val expandedPlanIds = MutableStateFlow<Set<String>>(emptySet())
    private val expandedDetailIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingRecipeKeys = MutableStateFlow<Set<String>>(emptySet())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val recentPlans = mealPlanSessionRepository.observeRecentCompletedPlans(RECENT_PLAN_LIMIT)
    private val availableLists = mealPlanSessionRepository.observeActiveLists()
        .map { lists -> lists.filter(::isUserSelectableList) }
    private val favouriteRecipes = combine(
        mealPlanSessionRepository.observeFavouriteRecipes(FAVOURITE_LIMIT),
        recentPlans,
    ) { favourites, plans ->
        buildFavouriteBrowserItems(favourites, plans)
    }
    private val filteredRecentPlans = combine(recentPlans, recentPlansQuery) { plans, query ->
        filterRecentPlans(plans, query)
    }
    private val filteredFavouriteRecipes = combine(favouriteRecipes, favouriteRecipesQuery) { favourites, query ->
        filterFavouriteRecipes(favourites, query)
    }
    private val browserChrome = combine(
        recentPlansQuery,
        favouriteRecipesQuery,
        expandedPlanIds,
        expandedDetailIds,
        pendingRecipeKeys,
    ) { recentQuery, favouriteQuery, planIds, detailIds, pendingKeys ->
        BrowserChrome(
            recentPlansQuery = recentQuery,
            favouriteRecipesQuery = favouriteQuery,
            expandedPlanIds = planIds,
            expandedDetailIds = detailIds,
            pendingRecipeKeys = pendingKeys,
        )
    }
    private val browserState = combine(
        filteredRecentPlans,
        filteredFavouriteRecipes,
        availableLists,
        browserChrome,
    ) { plans, favourites, lists, chrome ->
        MealPlansUiState(
            recentPlans = plans,
            favouriteRecipes = favourites,
            availableLists = lists,
            recentPlansQuery = chrome.recentPlansQuery,
            favouriteRecipesQuery = chrome.favouriteRecipesQuery,
            expandedPlanIds = chrome.expandedPlanIds,
            expandedDetailIds = chrome.expandedDetailIds,
            pendingRecipeKeys = chrome.pendingRecipeKeys,
        )
    }

    val uiState: StateFlow<MealPlansUiState> = combine(selectedTab, browserState) { tab, state ->
        state.copy(selectedTab = tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealPlansUiState())

    val messages = _messages.asSharedFlow()

    fun setTab(tab: MealPlansBrowserTab) {
        selectedTab.value = tab
    }

    fun updateRecentPlansQuery(query: String) {
        recentPlansQuery.value = query
    }

    fun updateFavouriteRecipesQuery(query: String) {
        favouriteRecipesQuery.value = query
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
            } catch (e: Exception) {
                Log.w(TAG, "toggleDayFavourite failed for $sessionId day ${day.dayIndex}", e)
                _messages.tryEmit("Couldn't update that favourite recipe.")
            } finally {
                pendingRecipeKeys.update { it - recipeKey }
            }
        }
    }

    fun addRecipeToLists(sessionId: String, dayIndex: Int, recipeKey: String) {
        if (!beginPending(recipeKey)) return
        viewModelScope.launch {
            try {
                val listName = mealPlanSessionRepository.recreateRecipeList(sessionId, dayIndex)
                _messages.tryEmit("Saved recipe to \"$listName\".")
            } catch (e: Exception) {
                Log.w(TAG, "addRecipeToLists failed for $sessionId day $dayIndex", e)
                _messages.tryEmit("Couldn't save that recipe to Lists.")
            } finally {
                pendingRecipeKeys.update { it - recipeKey }
            }
        }
    }

    fun addIngredientsToList(sessionId: String, dayIndex: Int, recipeKey: String, listId: Long) {
        if (!beginPending(recipeKey)) return
        viewModelScope.launch {
            try {
                val listName = mealPlanSessionRepository.addRecipeIngredientsToList(sessionId, dayIndex, listId)
                _messages.tryEmit("Added ingredients to \"$listName\".")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "addIngredientsToList rejected for $sessionId day $dayIndex list $listId", e)
                _messages.tryEmit(
                    e.message?.takeIf { it.endsWith("has no ingredient data to add.") }
                        ?: "Couldn't add those ingredients to your list.",
                )
            } catch (e: Exception) {
                Log.w(TAG, "addIngredientsToList failed for $sessionId day $dayIndex list $listId", e)
                _messages.tryEmit("Couldn't add those ingredients to your list.")
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
            } catch (e: Exception) {
                Log.w(TAG, "removeFavourite failed for $recipeKey", e)
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

    private fun filterRecentPlans(plans: List<MealPlanSnapshot>, query: String): List<MealPlanSnapshot> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return plans
        return plans.mapNotNull { plan ->
            val matchingDays = plan.days.filter { day ->
                matchesRecipeQuery(day.currentRecipe?.title ?: day.title, day.summary, normalizedQuery)
            }
            plan.takeIf { matchingDays.isNotEmpty() }?.let { snapshot ->
                val totalDays = snapshot.daysCount ?: snapshot.days.size
                snapshot.copy(daysCount = totalDays, days = matchingDays)
            }
        }
    }

    private fun filterFavouriteRecipes(
        favourites: List<FavouriteRecipeBrowserItem>,
        query: String,
    ): List<FavouriteRecipeBrowserItem> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return favourites
        return favourites.filter { favourite ->
            matchesRecipeQuery(favourite.title, favourite.summary, normalizedQuery)
        }
    }

    private fun matchesRecipeQuery(title: String?, summary: String?, query: String): Boolean {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return true
        return title.orEmpty().contains(normalizedQuery, ignoreCase = true) ||
            summary.orEmpty().contains(normalizedQuery, ignoreCase = true)
    }

    private fun isUserSelectableList(list: ListNameEntity): Boolean =
        !PLANNER_LIST_NAME_RE.containsMatchIn(list.name)

    companion object {
        private const val RECENT_PLAN_LIMIT = 12
        private const val FAVOURITE_LIMIT = 50
        private const val TAG = "MealPlansVM"
        private val PLANNER_LIST_NAME_RE = Regex("""^Meal Plan \d{4}-\d{2}-\d{2} \(MP-\d+\)""")

        fun dayDetailId(sessionId: String, dayIndex: Int): String = "plan:$sessionId:$dayIndex"
        fun favouriteDetailId(recipeKey: String): String = "favourite:$recipeKey"
    }
}
