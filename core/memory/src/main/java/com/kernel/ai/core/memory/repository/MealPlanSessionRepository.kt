package com.kernel.ai.core.memory.repository

import android.util.Log
import androidx.room.withTransaction
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.MealPlanDayDao
import com.kernel.ai.core.memory.dao.MealPlanFavouriteRecipeDao
import com.kernel.ai.core.memory.dao.MealPlanGroceryItemDao
import com.kernel.ai.core.memory.dao.MealPlanProjectionWriteDao
import com.kernel.ai.core.memory.dao.MealPlanRecipeVersionDao
import com.kernel.ai.core.memory.dao.MealPlanSessionDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.MealPlanDayEntity
import com.kernel.ai.core.memory.entity.MealPlanFavouriteRecipeEntity
import com.kernel.ai.core.memory.entity.MealPlanGroceryItemEntity
import com.kernel.ai.core.memory.entity.MealPlanProjectionWriteEntity
import com.kernel.ai.core.memory.entity.MealPlanRecipeVersionEntity
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import com.kernel.ai.core.memory.mealplan.CanonicalGroceryItem
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeMode
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeSummary
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.mealplan.PendingGenerationKind
import com.kernel.ai.core.memory.mealplan.RecentMealHistoryEntry
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import com.kernel.ai.core.memory.mealplan.jsonArrayToStringList
import com.kernel.ai.core.memory.mealplan.toJsonArrayString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class MealPlanIngredientDataUnavailableException(
    val dayIndex: Int,
) : IllegalArgumentException("Day ${dayIndex + 1} has no ingredient data to add.")

@Singleton
class MealPlanSessionRepository @Inject constructor(
    private val database: KernelDatabase,
    private val sessionDao: MealPlanSessionDao,
    private val dayDao: MealPlanDayDao,
    private val recipeVersionDao: MealPlanRecipeVersionDao,
    private val groceryItemDao: MealPlanGroceryItemDao,
    private val favouriteRecipeDao: MealPlanFavouriteRecipeDao,
    private val projectionWriteDao: MealPlanProjectionWriteDao,
    private val listItemDao: ListItemDao,
    private val listNameDao: ListNameDao,
) {
    private val sessionMutex = Mutex()

    suspend fun getActiveSession(conversationId: String): MealPlanSnapshot? =
        sessionDao.getActiveByConversation(conversationId)?.toSnapshot()

    suspend fun getSession(sessionId: String): MealPlanSnapshot? =
        sessionDao.getById(sessionId)?.toSnapshot()

    suspend fun hasActiveSessionForConversation(conversationId: String): Boolean =
        sessionDao.hasActiveForConversation(conversationId)

    suspend fun hasAnySessionForConversation(conversationId: String): Boolean =
        sessionDao.hasAnyForConversation(conversationId)

    suspend fun getRecentMealHistory(limit: Int): List<RecentMealHistoryEntry> =
        sessionDao.getRecentTerminalMealHistory(limit).map { row ->
            RecentMealHistoryEntry(
                title = row.title.trim(),
                summary = row.summary?.trim()?.takeIf { it.isNotBlank() },
                proteinTags = jsonArrayToStringList(row.proteinTagsJson),
            )
        }

    suspend fun getPendingCompletedSummarySessions(limit: Int): List<MealPlanSnapshot> =
        sessionDao.getCompletedWithoutSummary(limit).map { it.toSnapshot() }

    suspend fun getFavouriteRecipes(limit: Int): List<FavouriteRecipeSummary> =
        favouriteRecipeDao.getRecent(limit).map { favourite ->
            FavouriteRecipeSummary(
                recipeKey = favourite.recipeKey,
                title = favourite.title,
                summary = favourite.summary,
                proteinTags = jsonArrayToStringList(favourite.proteinTagsJson),
            )
        }

    fun observeFavouriteRecipes(limit: Int): Flow<List<FavouriteRecipeSummary>> =
        favouriteRecipeDao.observeRecent(limit).map { favourites ->
            favourites.map { favourite ->
                FavouriteRecipeSummary(
                    recipeKey = favourite.recipeKey,
                    title = favourite.title,
                    summary = favourite.summary,
                    proteinTags = jsonArrayToStringList(favourite.proteinTagsJson),
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)

    fun observeRecentCompletedPlans(limit: Int): Flow<List<MealPlanSnapshot>> =
        combine(sessionDao.observeCompleted(limit), favouriteRecipeDao.observeRecent(1)) { sessions, _ -> sessions }
            .mapLatest { sessions -> sessions.map { it.toSnapshot() } }

    fun observeActiveLists(): Flow<List<ListNameEntity>> =
        listNameDao.observeActiveLists()

    suspend fun recreateRecipeList(sessionId: String, dayIndex: Int): String = database.withTransaction {
        val (_, recipeVersion) = requireCurrentRecipeVersion(sessionId, dayIndex)
        val targetName = nextAvailableListName(recipeVersion.title.ifBlank { "Recipe" })
        val now = System.currentTimeMillis()
        val listId = listNameDao.insertAndGet(ListNameEntity(name = targetName, createdAt = now, updatedAt = now))
            .takeIf { it != -1L }
            ?: error("Failed to create recipe list: $targetName")
        buildRecipeListTexts(recipeVersion).forEach { text ->
            listItemDao.insert(
                ListItemEntity(listId = listId, text = text, createdAt = now, updatedAt = now),
            )
        }
        targetName
    }

    suspend fun addRecipeIngredientsToList(sessionId: String, dayIndex: Int, listId: Long): String = database.withTransaction {
        val list = requireNotNull(listNameDao.getById(listId)?.takeIf { it.archivedAt == null }) { "Unknown active list: $listId" }
        val (_, recipeVersion) = requireCurrentRecipeVersion(sessionId, dayIndex)
        val ingredientLines = groceryItemDao.getByRecipeVersion(recipeVersion.id)
            .map { grocery -> grocery.displayText.ifBlank { grocery.originalText } }
            .ifEmpty { buildRecipeIngredientTexts(recipeVersion) }
            .filter { it.isNotBlank() }
        if (ingredientLines.isEmpty()) {
            throw MealPlanIngredientDataUnavailableException(dayIndex)
        }
        val now = System.currentTimeMillis()
        ingredientLines.forEach { text ->
            listItemDao.insert(
                ListItemEntity(listId = listId, text = text, createdAt = now, updatedAt = now),
            )
        }
        listNameDao.updateTimestamp(listId, now)
        list.name
    }


    /**
     * Planner retention pruning runs here before resuming/creating a session so old terminal
     * planner state does not accumulate indefinitely between active planner uses.
     */
    suspend fun startOrResume(conversationId: String): MealPlanSnapshot = sessionMutex.withLock {
        runCatching { pruneTerminalSessions() }
            .onFailure { Log.w("MealPlanSessionRepo", "Failed to prune terminal sessions before startOrResume", it) }
        sessionDao.getActiveByConversation(conversationId)?.toSnapshot()
            ?: createSession(conversationId).toSnapshot()
    }

    suspend fun updateRequiredSlots(
        sessionId: String,
        peopleCount: Int? = null,
        daysCount: Int? = null,
        dietaryRestrictions: List<String>? = null,
        proteinPreferences: List<String>? = null,
        favouriteRecipeMode: FavouriteRecipeMode? = null,
    ): MealPlanSnapshot = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction session.toSnapshot()
        }
        val updated = session.copy(
            peopleCount = peopleCount ?: session.peopleCount,
            daysCount = daysCount ?: session.daysCount,
            dietaryRestrictionsJson = dietaryRestrictions?.distinct()?.toJsonArrayString() ?: session.dietaryRestrictionsJson,
            proteinPreferencesJson = proteinPreferences?.distinct()?.toJsonArrayString() ?: session.proteinPreferencesJson,
            favouriteRecipeMode = favouriteRecipeMode?.name ?: session.favouriteRecipeMode,
            updatedAt = System.currentTimeMillis(),
        )
        sessionDao.update(updated)
        updated.toSnapshot()
    }

    suspend fun setRecipeFavourite(
        sessionId: String,
        dayIndex: Int,
        favourite: Boolean,
    ): MealPlanSnapshot = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction session.toSnapshot()
        }
        val day = requireNotNull(dayDao.getBySessionAndIndex(sessionId, dayIndex)) {
            "Unknown meal-plan day $dayIndex for session $sessionId"
        }
        val recipeVersion = requireNotNull(currentRecipeVersion(day)) {
            "Day ${dayIndex + 1} does not have a recipe yet."
        }
        val recipeKey = resolvedRecipeKey(recipeVersion, jsonArrayToStringList(day.proteinTagsJson))
        require(recipeKey.isNotBlank()) { "Day ${dayIndex + 1} does not have a stable favourite-recipe key yet." }
        val now = System.currentTimeMillis()
        if (favourite) {
            favouriteRecipeDao.upsert(
                MealPlanFavouriteRecipeEntity(
                    recipeKey = recipeKey,
                    title = recipeVersion.title,
                    summary = day.summary,
                    proteinTagsJson = day.proteinTagsJson,
                    createdAt = favouriteRecipeDao.getByKey(recipeKey)?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        } else {
            favouriteRecipeDao.delete(recipeKey)
        }
        if (session.status == MealPlanSessionStatus.COMPLETED.name) {
            return@withTransaction session.toSnapshot()
        }
        val updatedSession = session.copy(updatedAt = now)
        sessionDao.update(updatedSession)
        updatedSession.toSnapshot()
    }

    suspend fun removeFavouriteRecipe(recipeKey: String) = database.withTransaction {
        favouriteRecipeDao.delete(recipeKey)
    }

    suspend fun savePlanDraft(
        sessionId: String,
        days: List<MealPlanDraftDay>,
    ): MealPlanSnapshot = database.withTransaction {
        val now = System.currentTimeMillis()
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        projectionWriteDao.getActiveTargetNamesForSession(sessionId).forEach { deleteList(it) }
        projectionWriteDao.markSupersededForSession(sessionId, now)
        dayDao.deleteBySession(sessionId)
        dayDao.insertAll(
            days.sortedBy { it.dayIndex }.map { day ->
                MealPlanDayEntity(
                    id = UUID.randomUUID().toString(),
                    mealPlanSessionId = sessionId,
                    dayIndex = day.dayIndex,
                    title = day.title,
                    summary = day.summary,
                    proteinTagsJson = day.proteinTags.distinct().toJsonArrayString(),
                    status = MealPlanDayStatus.DRAFTED.name,
                    currentRecipeVersion = null,
                    attemptCount = 0,
                    lastErrorCode = null,
                    lastErrorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        val updatedSession = session.copy(
            status = MealPlanSessionStatus.PLAN_REVIEW.name,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            activeDayIndex = null,
            planVersion = session.planVersion + 1,
            updatedAt = now,
        )
        sessionDao.update(updatedSession)
        updatedSession.toSnapshot()
    }

    suspend fun replaceDayDraft(
        sessionId: String,
        dayIndex: Int,
        title: String,
        summary: String?,
        proteinTags: List<String>,
        recipeGenerationPending: Boolean = false,
    ): MealPlanSnapshot = database.withTransaction {
        val now = System.currentTimeMillis()
        val existing = requireNotNull(dayDao.getBySessionAndIndex(sessionId, dayIndex)) {
            "Unknown meal-plan day $dayIndex for session $sessionId"
        }
        dayDao.update(
            existing.copy(
                title = title,
                summary = summary,
                proteinTagsJson = proteinTags.distinct().toJsonArrayString(),
                status = MealPlanDayStatus.DRAFTED.name,
                updatedAt = now,
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )
        val session = requireNotNull(sessionDao.getById(sessionId))
        val updatedSession = session.copy(
            status = if (recipeGenerationPending) {
                MealPlanSessionStatus.RECIPES_IN_PROGRESS.name
            } else {
                MealPlanSessionStatus.PLAN_REVIEW.name
            },
            activeDayIndex = if (recipeGenerationPending) dayIndex else null,
            pendingGenerationKind = if (recipeGenerationPending) PendingGenerationKind.RECIPE.name else null,
            pendingGenerationDayIndex = if (recipeGenerationPending) dayIndex else null,
            pendingGenerationStartedAt = if (recipeGenerationPending) now else null,
            updatedAt = now,
        )
        sessionDao.update(updatedSession)
        updatedSession.toSnapshot()
    }

    suspend fun returnToSlotCollection(sessionId: String): MealPlanSnapshot = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction session.toSnapshot()
        }
        val updated = session.copy(
            status = MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS.name,
            activeDayIndex = null,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        sessionDao.update(updated)
        updated.toSnapshot()
    }

    suspend fun persistRecipeDraft(
        sessionId: String,
        dayIndex: Int,
        recipeDraft: RecipeDraft,
        rawModelJson: String,
        groceries: List<CanonicalGroceryItem>,
    ): MealPlanSnapshot = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction session.toSnapshot()
        }
        val now = System.currentTimeMillis()
        val day = requireNotNull(dayDao.getBySessionAndIndex(sessionId, dayIndex)) {
            "Unknown meal-plan day $dayIndex for session $sessionId"
        }
        val nextVersion = (recipeVersionDao.getLatestForDay(day.id)?.version ?: 0) + 1
        val recipeVersion = MealPlanRecipeVersionEntity(
            id = UUID.randomUUID().toString(),
            mealPlanSessionId = sessionId,
            mealPlanDayId = day.id,
            version = nextVersion,
            recipeKey = normalizeRecipeKey(recipeDraft.title, jsonArrayToStringList(day.proteinTagsJson)),
            title = recipeDraft.title,
            servings = recipeDraft.servings,
            ingredientsJson = ingredientsToJson(recipeDraft.ingredients),
            methodStepsJson = methodStepsToJson(recipeDraft.methodSteps),
            rawModelJson = rawModelJson,
            createdAt = now,
        )
        recipeVersionDao.insert(recipeVersion)
        groceryItemDao.insertAll(
            groceries.mapIndexed { index, item ->
                MealPlanGroceryItemEntity(
                    id = UUID.randomUUID().toString(),
                    mealPlanSessionId = sessionId,
                    mealPlanDayId = day.id,
                    recipeVersionId = recipeVersion.id,
                    ingredientIndex = index,
                    displayText = item.displayText,
                    originalText = item.originalText,
                    quantity = item.quantity,
                    unit = item.unit,
                    ingredientName = item.ingredientName,
                    note = item.note,
                    normalizationStatus = item.normalizationStatus.name,
                    mergeKey = item.mergeKey,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        dayDao.update(
            day.copy(
                status = MealPlanDayStatus.PERSISTED.name,
                currentRecipeVersion = nextVersion,
                attemptCount = day.attemptCount + 1,
                lastErrorCode = null,
                lastErrorMessage = null,
                updatedAt = now,
            ),
        )
        rebuildRecipeProjection(session, day.id, dayIndex, recipeVersion)
        rebuildShoppingProjection(sessionId)

        val currentSession = requireNotNull(sessionDao.getById(sessionId))
        val nextPending = dayDao.getBySession(sessionId)
            .firstOrNull { it.status != MealPlanDayStatus.PERSISTED.name && it.status != MealPlanDayStatus.FAILED.name }
        val updatedSession = if (nextPending == null) {
            currentSession.copy(
                status = MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY.name,
                activeDayIndex = null,
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                updatedAt = now,
            )
        } else {
            currentSession.copy(
                status = MealPlanSessionStatus.RECIPES_IN_PROGRESS.name,
                activeDayIndex = nextPending.dayIndex,
                pendingGenerationKind = PendingGenerationKind.RECIPE.name,
                pendingGenerationDayIndex = nextPending.dayIndex,
                pendingGenerationStartedAt = null,
                updatedAt = now,
            )
        }
        sessionDao.update(updatedSession)
        updatedSession.toSnapshot()
    }

    suspend fun markGenerationFailure(
        sessionId: String,
        dayIndex: Int?,
        errorCode: String,
        errorMessage: String,
    ): MealPlanSnapshot = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId))
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction session.toSnapshot()
        }
        val now = System.currentTimeMillis()
        if (dayIndex != null) {
            dayDao.getBySessionAndIndex(sessionId, dayIndex)?.let { day ->
                dayDao.update(
                    day.copy(
                        status = MealPlanDayStatus.FAILED.name,
                        attemptCount = day.attemptCount + 1,
                        lastErrorCode = errorCode,
                        lastErrorMessage = errorMessage,
                        updatedAt = now,
                    ),
                )
            }
        }
        val updated = session.copy(
            status = if (dayIndex == null) {
                MealPlanSessionStatus.PLAN_REVIEW.name
            } else {
                MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY.name
            },
            activeDayIndex = dayIndex,
            pendingGenerationKind = if (dayIndex == null) null else PendingGenerationKind.RECIPE.name,
            pendingGenerationDayIndex = dayIndex,
            pendingGenerationStartedAt = null,
            updatedAt = now,
        )
        sessionDao.update(updated)
        updated.toSnapshot()
    }

    suspend fun markPendingGeneration(
        sessionId: String,
        kind: PendingGenerationKind,
        dayIndex: Int? = null,
    ) = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId))
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction
        }
        val now = System.currentTimeMillis()
        sessionDao.update(
            session.copy(
                status = if (kind == PendingGenerationKind.PLAN) {
                    MealPlanSessionStatus.PLAN_REVIEW.name
                } else {
                    MealPlanSessionStatus.RECIPES_IN_PROGRESS.name
                },
                activeDayIndex = dayIndex,
                pendingGenerationKind = kind.name,
                pendingGenerationDayIndex = dayIndex,
                pendingGenerationStartedAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun clearPendingGeneration(sessionId: String) = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId))
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction
        }
        val restoringPlanReview =
            session.pendingGenerationKind == PendingGenerationKind.REPLACEMENT.name &&
                dayDao.getBySession(sessionId).all { it.status == MealPlanDayStatus.DRAFTED.name }
        sessionDao.update(
            session.copy(
                status = if (restoringPlanReview) MealPlanSessionStatus.PLAN_REVIEW.name else session.status,
                activeDayIndex = if (restoringPlanReview) null else session.activeDayIndex,
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markFinalSummaryWritten(sessionId: String) = database.withTransaction {
        val session = requireNotNull(sessionDao.getById(sessionId))
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return@withTransaction
        }
        sessionDao.update(
            session.copy(
                finalSummaryWritten = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun completeSession(sessionId: String): MealPlanSnapshot {
        val updated = database.withTransaction {
            val session = requireNotNull(sessionDao.getById(sessionId))
            if (session.status == MealPlanSessionStatus.CANCELLED.name) {
                return@withTransaction session.toSnapshot()
            }
            val now = System.currentTimeMillis()
            val next = session.copy(
                status = MealPlanSessionStatus.COMPLETED.name,
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                activeDayIndex = null,
                updatedAt = now,
                completedAt = now,
            )
            sessionDao.update(next)
            reconcileCanonicalProjections(next)
            next.toSnapshot()
        }
        updated.completedAt?.let { completedAt ->
            runCatching { pruneTerminalSessions(completedAt) }
                .onFailure { Log.w("MealPlanSessionRepo", "Failed to prune terminal sessions after completion", it) }
        }
        return updated
    }

    suspend fun cancelSession(sessionId: String): MealPlanSnapshot {
        val updated = database.withTransaction {
            val session = requireNotNull(sessionDao.getById(sessionId))
            val now = System.currentTimeMillis()
            val next = session.copy(
                status = MealPlanSessionStatus.CANCELLED.name,
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                activeDayIndex = null,
                updatedAt = now,
                cancelledAt = now,
            )
            deleteActiveProjections(sessionId, supersededAt = now)
            sessionDao.update(next)
            next.toSnapshot()
        }
        runCatching { pruneTerminalSessions(requireNotNull(updated.cancelledAt)) }
            .onFailure { Log.w("MealPlanSessionRepo", "Failed to prune terminal sessions after cancellation", it) }
        return updated
    }

    suspend fun getNextPendingDay(sessionId: String): MealPlanDayEntity? =
        dayDao.getBySession(sessionId)
            .firstOrNull { it.status != MealPlanDayStatus.PERSISTED.name && it.status != MealPlanDayStatus.FAILED.name }

    suspend fun buildFinalSummary(sessionId: String): String {
        val snapshot = requireNotNull(getSession(sessionId))
        val restrictions = snapshot.dietaryRestrictions.takeIf { it.isNotEmpty() }?.joinToString() ?: "no specific dietary restrictions"
        val proteins = snapshot.proteinPreferences.takeIf { it.isNotEmpty() }?.joinToString() ?: "mixed proteins"
        val daySummary = snapshot.days.joinToString("; ") { day ->
            "Day ${day.dayIndex + 1}: ${day.title ?: "Meal"}"
        }
        return "Created a ${snapshot.daysCount ?: snapshot.days.size}-day meal plan for ${snapshot.peopleCount ?: 0} people with $restrictions and protein preferences $proteins. Plan: $daySummary."
    }

    private suspend fun rebuildShoppingProjection(sessionId: String) {
        val session = requireNotNull(sessionDao.getById(sessionId))
        val targetName = shoppingListName(session)
        database.withTransaction {
            val oldTargets = projectionWriteDao.getActiveTargetNamesForTarget(sessionId, TARGET_KIND_PLAN_SHOPPING_LIST)
            oldTargets.forEach { deleteList(it) }
            if (targetName !in oldTargets) {
                deleteList(targetName)
            }
            val now = System.currentTimeMillis()
            val projectedAt = now
            projectionWriteDao.markSupersededForTarget(sessionId, TARGET_KIND_PLAN_SHOPPING_LIST, projectedAt)
            val listId = listNameDao.insertAndGet(ListNameEntity(name = targetName, createdAt = now, updatedAt = now))
                .takeIf { it != -1L }
                ?: error("Failed to create shopping projection list: $targetName")
            val groceries = groceryItemDao.getCurrentForSession(sessionId)
            groceries.forEach { grocery ->
                listItemDao.insert(
                    ListItemEntity(listId = listId, text = grocery.displayText, createdAt = now, updatedAt = now),
                )
            }
            projectionWriteDao.insertAll(
                groceries.map { grocery ->
                    MealPlanProjectionWriteEntity(
                        id = UUID.randomUUID().toString(),
                        mealPlanSessionId = sessionId,
                        targetKind = TARGET_KIND_PLAN_SHOPPING_LIST,
                        targetName = targetName,
                        sourceKey = "day:${grocery.mealPlanDayId}:grocery:${grocery.id}",
                        sourceVersion = requireNotNull(recipeVersionDao.getById(grocery.recipeVersionId)) {
                            "Missing recipe version ${grocery.recipeVersionId}"
                        }.version,
                        listItemId = null,
                        projectedAt = projectedAt,
                        supersededAt = null,
                    )
                },
            )
        }
    }

    private suspend fun rebuildRecipeProjection(
        session: MealPlanSessionEntity,
        dayId: String,
        dayIndex: Int,
        recipeVersion: MealPlanRecipeVersionEntity,
    ) {
        val sourcePrefix = "day:$dayId:%"
        val targetName = recipeListName(session, dayIndex, recipeVersion.title)
        val ingredients = jsonToIngredients(recipeVersion.ingredientsJson)
        val methodSteps = jsonToMethodSteps(recipeVersion.methodStepsJson)
        val recipeListItems = buildList<Pair<String, String>> {
            add("day:$dayId:recipe:${recipeVersion.id}:section:ingredients" to "Ingredients")
            ingredients.forEachIndexed { index, ingredient ->
                add("day:$dayId:recipe:${recipeVersion.id}:ingredient:$index" to ingredient.originalText)
            }
            add("day:$dayId:recipe:${recipeVersion.id}:section:method" to "Method")
            methodSteps.forEachIndexed { index, step ->
                add("day:$dayId:recipe:${recipeVersion.id}:step:$index" to "${step.stepNumber}. ${step.text}")
            }
        }
        database.withTransaction {
            val oldTargets = projectionWriteDao.getActiveTargetNamesForSourcePrefix(
                sessionId = session.id,
                targetKind = TARGET_KIND_RECIPE_LIST,
                sourceKeyPrefix = sourcePrefix,
            )
            oldTargets.forEach { deleteList(it) }
            if (targetName !in oldTargets) {
                deleteList(targetName)
            }
            val now = System.currentTimeMillis()
            val projectedAt = now
            projectionWriteDao.markSupersededForSourcePrefix(
                sessionId = session.id,
                targetKind = TARGET_KIND_RECIPE_LIST,
                sourceKeyPrefix = sourcePrefix,
                timestamp = projectedAt,
            )
            val listId = listNameDao.insertAndGet(ListNameEntity(name = targetName, createdAt = now, updatedAt = now))
                .takeIf { it != -1L }
                ?: error("Failed to create recipe projection list: $targetName")
            recipeListItems.forEach { (_, text) ->
                listItemDao.insert(
                    ListItemEntity(listId = listId, text = text, createdAt = now, updatedAt = now),
                )
            }
            projectionWriteDao.insertAll(
                recipeListItems.map { (sourceKey, _) ->
                    MealPlanProjectionWriteEntity(
                        id = UUID.randomUUID().toString(),
                        mealPlanSessionId = session.id,
                        targetKind = TARGET_KIND_RECIPE_LIST,
                        targetName = targetName,
                        sourceKey = sourceKey,
                        sourceVersion = recipeVersion.version,
                        listItemId = null,
                        projectedAt = projectedAt,
                        supersededAt = null,
                    )
                },
            )
        }
    }

    private suspend fun deleteList(name: String) {
        // With FK CASCADE on list_items.listId, deleting from lists removes all child items
        listNameDao.deleteByName(name)
    }

    private suspend fun deleteActiveProjections(sessionId: String, supersededAt: Long) {
        database.withTransaction {
            for (targetName in projectionWriteDao.getActiveTargetNamesForSession(sessionId)) {
                deleteList(targetName)
            }
            projectionWriteDao.markSupersededForSession(sessionId, supersededAt)
        }
    }

    private suspend fun reconcileCanonicalProjections(session: MealPlanSessionEntity) {
        val persistedDays = dayDao.getBySession(session.id)
            .filter { day -> day.status == MealPlanDayStatus.PERSISTED.name && day.currentRecipeVersion != null }
            .sortedBy { it.dayIndex }
        deleteActiveProjections(session.id, supersededAt = System.currentTimeMillis())
        if (persistedDays.isEmpty()) {
            return
        }
        persistedDays.forEach { day ->
            val recipeVersionNumber = requireNotNull(day.currentRecipeVersion)
            val recipeVersion = requireNotNull(recipeVersionDao.getByDayAndVersion(day.id, recipeVersionNumber)) {
                "Missing canonical recipe version $recipeVersionNumber for meal-plan day ${day.id}"
            }
            rebuildRecipeProjection(session, day.id, day.dayIndex, recipeVersion)
        }
        rebuildShoppingProjection(session.id)
    }

    /**
     * Conservative planner retention: completed sessions are pruned after 30 days once the
     * final summary has been written; cancelled sessions are pruned after 7 days. This runs on
     * planner start/resume and after terminal writes (complete/cancel).
     */
    private suspend fun pruneTerminalSessions(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            val prunable = sessionDao.getPrunableTerminalSessions(
                completedBefore = now - COMPLETED_SESSION_RETENTION_MS,
                completedWithoutSummaryBefore = now - COMPLETED_SESSION_FALLBACK_RETENTION_MS,
                cancelledBefore = now - CANCELLED_SESSION_RETENTION_MS,
            )
            prunable.forEach { session ->
                projectionWriteDao.getAllTargetNamesForSession(session.id).forEach { deleteList(it) }
                projectionWriteDao.deleteBySessionId(session.id)
                sessionDao.deleteById(session.id)
            }
            prunable.size
        }

    private suspend fun createSession(conversationId: String): MealPlanSessionEntity =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val entity = MealPlanSessionEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                status = MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS.name,
                peopleCount = null,
                daysCount = null,
                dietaryRestrictionsJson = "[]",
                proteinPreferencesJson = "[]",
                optionalSlotsJson = "{}",
                favouriteRecipeMode = FavouriteRecipeMode.NONE.name,
                activeDayIndex = null,
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                displayCode = (sessionDao.getMaxDisplayCode() ?: 0) + 1,
                planVersion = 0,
                finalSummaryWritten = false,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
                cancelledAt = null,
            )
            sessionDao.insert(entity)
            entity
        }

    private suspend fun MealPlanSessionEntity.toSnapshot(): MealPlanSnapshot {
        val days = dayDao.getBySession(id)
        val dayRecipes = days.associateWith { day -> currentRecipeVersion(day) }
        val favouriteKeys = dayRecipes.entries
            .mapNotNull { (day, recipeVersion) ->
                recipeVersion?.let { current ->
                    resolvedRecipeKey(current, jsonArrayToStringList(day.proteinTagsJson)).takeIf { it.isNotBlank() }
                }
            }
            .distinct()
        val favouriteKeySet = if (favouriteKeys.isEmpty()) {
            emptySet()
        } else {
            favouriteRecipeDao.getExistingKeys(favouriteKeys).toSet()
        }
        return MealPlanSnapshot(
            sessionId = id,
            conversationId = conversationId,
            displayName = canonicalDisplayName(this),
            status = MealPlanSessionStatus.valueOf(status),
            peopleCount = peopleCount,
            daysCount = daysCount,
            dietaryRestrictions = jsonArrayToStringList(dietaryRestrictionsJson),
            proteinPreferences = jsonArrayToStringList(proteinPreferencesJson),
            favouriteRecipeMode = FavouriteRecipeMode.valueOf(favouriteRecipeMode),
            activeDayIndex = activeDayIndex,
            pendingGenerationKind = pendingGenerationKind?.let(PendingGenerationKind::valueOf),
            pendingGenerationDayIndex = pendingGenerationDayIndex,
            planVersion = planVersion,
            finalSummaryWritten = finalSummaryWritten,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt,
            cancelledAt = cancelledAt,
            days = days.map { day ->
                val currentRecipeVersionEntity = dayRecipes.getValue(day)
                val recipe = currentRecipeVersionEntity?.let { current ->
                    RecipeDraft(
                        title = current.title,
                        servings = current.servings,
                        ingredients = jsonToIngredients(current.ingredientsJson),
                        methodSteps = jsonToMethodSteps(current.methodStepsJson),
                    )
                }
                val recipeKey = currentRecipeVersionEntity?.let { current ->
                    resolvedRecipeKey(current, jsonArrayToStringList(day.proteinTagsJson)).takeIf { it.isNotBlank() }
                }
                MealPlanSnapshotDay(
                    id = day.id,
                    dayIndex = day.dayIndex,
                    title = day.title,
                    summary = day.summary,
                    proteinTags = jsonArrayToStringList(day.proteinTagsJson),
                    status = MealPlanDayStatus.valueOf(day.status),
                    currentRecipeVersion = day.currentRecipeVersion,
                    attemptCount = day.attemptCount,
                    lastErrorCode = day.lastErrorCode,
                    lastErrorMessage = day.lastErrorMessage,
                    currentRecipe = recipe,
                    recipeKey = recipeKey,
                    isFavouriteRecipe = recipeKey != null && recipeKey in favouriteKeySet,
                )
            },
        )
    }

    private fun canonicalDisplayName(session: MealPlanSessionEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(session.createdAt))
        return "Meal Plan $date (${planCode(session.displayCode)})"
    }

    private fun planCode(displayCode: Int): String = String.format(Locale.ENGLISH, "MP-%03d", displayCode)

    private fun shoppingListName(session: MealPlanSessionEntity): String =
        "${canonicalDisplayName(session)} Shopping List"

    private fun recipeListName(session: MealPlanSessionEntity, dayIndex: Int, title: String): String =
        "${canonicalDisplayName(session)} Day ${dayIndex + 1} — $title"

    private suspend fun currentRecipeVersion(day: MealPlanDayEntity): MealPlanRecipeVersionEntity? {
        val version = day.currentRecipeVersion ?: return null
        return recipeVersionDao.getLatestForDay(day.id)?.takeIf { it.version == version }
    }

    private suspend fun requireCurrentRecipeVersion(
        sessionId: String,
        dayIndex: Int,
    ): Pair<MealPlanDayEntity, MealPlanRecipeVersionEntity> {
        val day = requireNotNull(dayDao.getBySessionAndIndex(sessionId, dayIndex)) {
            "Unknown meal-plan day $dayIndex for session $sessionId"
        }
        val recipeVersion = requireNotNull(currentRecipeVersion(day)) {
            "Day ${dayIndex + 1} does not have a recipe yet."
        }
        return day to recipeVersion
    }

    private fun buildRecipeListTexts(recipeVersion: MealPlanRecipeVersionEntity): List<String> = buildList {
        add("Ingredients")
        addAll(buildRecipeIngredientTexts(recipeVersion))
        add("Method")
        addAll(buildRecipeMethodTexts(recipeVersion))
    }

    private fun buildRecipeIngredientTexts(recipeVersion: MealPlanRecipeVersionEntity): List<String> =
        jsonToIngredients(recipeVersion.ingredientsJson).map { ingredient -> ingredient.originalText }

    private fun buildRecipeMethodTexts(recipeVersion: MealPlanRecipeVersionEntity): List<String> =
        jsonToMethodSteps(recipeVersion.methodStepsJson).map { step -> "${step.stepNumber}. ${step.text}" }

    private suspend fun nextAvailableListName(baseName: String): String {
        val normalizedBaseName = baseName.trim().ifBlank { "Recipe" }
        var candidate = normalizedBaseName
        var suffix = 2
        while (listNameDao.getByName(candidate) != null) {
            candidate = "$normalizedBaseName ($suffix)"
            suffix += 1
        }
        return candidate
    }

    private fun resolvedRecipeKey(recipeVersion: MealPlanRecipeVersionEntity, proteinTags: List<String>): String =
        recipeVersion.recipeKey.takeIf { it.isNotBlank() }
            ?: normalizeRecipeKey(recipeVersion.title, proteinTags)

    private fun normalizeRecipeKey(title: String, proteinTags: List<String>): String {
        val normalizedTitle = title.lowercase(Locale.ENGLISH)
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTISPACE_REGEX, " ")
            .trim()
        val normalizedProteins = proteinTags.map { it.lowercase(Locale.ENGLISH).trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        return listOf(normalizedTitle, normalizedProteins.joinToString("-"))
            .filter { it.isNotBlank() }
            .joinToString("|")
    }

    private fun ingredientsToJson(ingredients: List<RecipeDraftIngredient>): String = JSONArray(
        ingredients.map { ingredient ->
            JSONObject().apply {
                put("original_text", ingredient.originalText)
                put("amount", ingredient.amount)
                put("unit", ingredient.unit)
                put("item", ingredient.item)
                put("note", ingredient.note)
            }
        },
    ).toString()

    private fun methodStepsToJson(steps: List<RecipeDraftMethodStep>): String = JSONArray(
        steps.map { step ->
            JSONObject().apply {
                put("step_number", step.stepNumber)
                put("text", step.text)
            }
        },
    ).toString()

    private fun jsonToIngredients(raw: String): List<RecipeDraftIngredient> =
        JSONArray(raw).let { arr ->
            List(arr.length()) { index ->
                arr.getJSONObject(index).let { obj ->
                    RecipeDraftIngredient(
                        originalText = obj.optString("original_text"),
                        amount = obj.optString("amount").takeIf { it.isNotBlank() && it != "null" },
                        unit = obj.optString("unit").takeIf { it.isNotBlank() && it != "null" },
                        item = obj.optString("item").takeIf { it.isNotBlank() && it != "null" },
                        note = obj.optString("note").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
        }

    private fun jsonToMethodSteps(raw: String): List<RecipeDraftMethodStep> =
        JSONArray(raw).let { arr ->
            List(arr.length()) { index ->
                arr.getJSONObject(index).let { obj ->
                    RecipeDraftMethodStep(
                        stepNumber = obj.optInt("step_number", index + 1),
                        text = obj.optString("text"),
                    )
                }
            }
        }

    private companion object {
        private const val TARGET_KIND_PLAN_SHOPPING_LIST = "PLAN_SHOPPING_LIST"
        private const val TARGET_KIND_RECIPE_LIST = "RECIPE_LIST"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val COMPLETED_SESSION_RETENTION_MS = 30L * DAY_MS
        private const val COMPLETED_SESSION_FALLBACK_RETENTION_MS = 90L * DAY_MS
        private const val CANCELLED_SESSION_RETENTION_MS = 7L * DAY_MS
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9 ]")
        private val MULTISPACE_REGEX = Regex("\\s+")
    }
}
