package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.MealPlanDayDao
import com.kernel.ai.core.memory.dao.MealPlanGroceryItemDao
import com.kernel.ai.core.memory.dao.MealPlanProjectionWriteDao
import com.kernel.ai.core.memory.dao.MealPlanRecipeVersionDao
import com.kernel.ai.core.memory.dao.MealPlanSessionDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.MealPlanDayEntity
import com.kernel.ai.core.memory.entity.MealPlanGroceryItemEntity
import com.kernel.ai.core.memory.entity.MealPlanProjectionWriteEntity
import com.kernel.ai.core.memory.entity.MealPlanRecipeVersionEntity
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import com.kernel.ai.core.memory.mealplan.CanonicalGroceryItem
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.mealplan.PendingGenerationKind
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import com.kernel.ai.core.memory.mealplan.jsonArrayToStringList
import com.kernel.ai.core.memory.mealplan.toJsonArrayString
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

@Singleton
class MealPlanSessionRepository @Inject constructor(
    private val sessionDao: MealPlanSessionDao,
    private val dayDao: MealPlanDayDao,
    private val recipeVersionDao: MealPlanRecipeVersionDao,
    private val groceryItemDao: MealPlanGroceryItemDao,
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

    suspend fun startOrResume(conversationId: String): MealPlanSnapshot = sessionMutex.withLock {
        sessionDao.getActiveByConversation(conversationId)?.toSnapshot()
            ?: createSession(conversationId).toSnapshot()
    }

    suspend fun updateRequiredSlots(
        sessionId: String,
        peopleCount: Int? = null,
        daysCount: Int? = null,
        dietaryRestrictions: List<String>? = null,
        proteinPreferences: List<String>? = null,
    ): MealPlanSnapshot {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        val updated = session.copy(
            peopleCount = peopleCount ?: session.peopleCount,
            daysCount = daysCount ?: session.daysCount,
            dietaryRestrictionsJson = dietaryRestrictions?.distinct()?.toJsonArrayString() ?: session.dietaryRestrictionsJson,
            proteinPreferencesJson = proteinPreferences?.distinct()?.toJsonArrayString() ?: session.proteinPreferencesJson,
            updatedAt = System.currentTimeMillis(),
        )
        sessionDao.update(updated)
        return updated.toSnapshot()
    }

    suspend fun savePlanDraft(
        sessionId: String,
        days: List<MealPlanDraftDay>,
    ): MealPlanSnapshot {
        val now = System.currentTimeMillis()
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
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
        return updatedSession.toSnapshot()
    }

    suspend fun replaceDayDraft(
        sessionId: String,
        dayIndex: Int,
        title: String,
        summary: String?,
        proteinTags: List<String>,
    ): MealPlanSnapshot {
        val existing = requireNotNull(dayDao.getBySessionAndIndex(sessionId, dayIndex)) {
            "Unknown meal-plan day $dayIndex for session $sessionId"
        }
        dayDao.update(
            existing.copy(
                title = title,
                summary = summary,
                proteinTagsJson = proteinTags.distinct().toJsonArrayString(),
                status = MealPlanDayStatus.DRAFTED.name,
                updatedAt = System.currentTimeMillis(),
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )
        val session = requireNotNull(sessionDao.getById(sessionId))
        val updatedSession = session.copy(
            status = MealPlanSessionStatus.PLAN_REVIEW.name,
            activeDayIndex = null,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        sessionDao.update(updatedSession)
        return updatedSession.toSnapshot()
    }

    suspend fun returnToSlotCollection(sessionId: String): MealPlanSnapshot {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        val updated = session.copy(
            status = MealPlanSessionStatus.COLLECTING_REQUIRED_SLOTS.name,
            activeDayIndex = null,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        sessionDao.update(updated)
        return updated.toSnapshot()
    }

    suspend fun persistRecipeDraft(
        sessionId: String,
        dayIndex: Int,
        recipeDraft: RecipeDraft,
        rawModelJson: String,
        groceries: List<CanonicalGroceryItem>,
    ): MealPlanSnapshot {
        val session = requireNotNull(sessionDao.getById(sessionId)) { "Unknown meal-plan session: $sessionId" }
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return session.toSnapshot()
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
        rebuildRecipeProjection(sessionId, day.id, dayIndex, recipeVersion)
        rebuildShoppingProjection(sessionId)

        val currentSession = requireNotNull(sessionDao.getById(sessionId))
        val nextPending = dayDao.getBySession(sessionId)
            .firstOrNull { it.status != MealPlanDayStatus.PERSISTED.name }
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
        return updatedSession.toSnapshot()
    }

    suspend fun markGenerationFailure(
        sessionId: String,
        dayIndex: Int?,
        errorCode: String,
        errorMessage: String,
    ): MealPlanSnapshot {
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
        val session = requireNotNull(sessionDao.getById(sessionId))
        val updated = session.copy(
            status = MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY.name,
            activeDayIndex = dayIndex,
            pendingGenerationKind = if (dayIndex == null) PendingGenerationKind.PLAN.name else PendingGenerationKind.RECIPE.name,
            pendingGenerationDayIndex = dayIndex,
            pendingGenerationStartedAt = null,
            updatedAt = now,
        )
        sessionDao.update(updated)
        return updated.toSnapshot()
    }

    suspend fun markPendingGeneration(
        sessionId: String,
        kind: PendingGenerationKind,
        dayIndex: Int? = null,
    ) {
        val session = requireNotNull(sessionDao.getById(sessionId))
        if (session.status == MealPlanSessionStatus.CANCELLED.name) {
            return
        }
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
                pendingGenerationStartedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearPendingGeneration(sessionId: String) {
        val session = requireNotNull(sessionDao.getById(sessionId))
        sessionDao.update(
            session.copy(
                pendingGenerationKind = null,
                pendingGenerationDayIndex = null,
                pendingGenerationStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markFinalSummaryWritten(sessionId: String) {
        val session = requireNotNull(sessionDao.getById(sessionId))
        sessionDao.update(
            session.copy(
                finalSummaryWritten = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun completeSession(sessionId: String): MealPlanSnapshot {
        val session = requireNotNull(sessionDao.getById(sessionId))
        val now = System.currentTimeMillis()
        val updated = session.copy(
            status = MealPlanSessionStatus.COMPLETED.name,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            activeDayIndex = null,
            updatedAt = now,
            completedAt = now,
        )
        sessionDao.update(updated)
        return updated.toSnapshot()
    }

    suspend fun cancelSession(sessionId: String): MealPlanSnapshot {
        val session = requireNotNull(sessionDao.getById(sessionId))
        val now = System.currentTimeMillis()
        val updated = session.copy(
            status = MealPlanSessionStatus.CANCELLED.name,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            activeDayIndex = null,
            updatedAt = now,
            cancelledAt = now,
        )
        sessionDao.update(updated)
        return updated.toSnapshot()
    }

    suspend fun getNextPendingDay(sessionId: String): MealPlanDayEntity? =
        dayDao.getBySession(sessionId).firstOrNull { it.status != MealPlanDayStatus.PERSISTED.name }

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
        deleteList(targetName)
        listNameDao.insert(ListNameEntity(name = targetName))
        val projectedAt = System.currentTimeMillis()
        projectionWriteDao.markSupersededForTarget(sessionId, TARGET_KIND_PLAN_SHOPPING_LIST, projectedAt)
        val groceries = groceryItemDao.getCurrentForSession(sessionId)
        groceries.forEach { grocery ->
            listItemDao.insert(ListItemEntity(listName = targetName, item = grocery.displayText))
        }
        projectionWriteDao.insertAll(
            groceries.map { grocery ->
                MealPlanProjectionWriteEntity(
                    id = UUID.randomUUID().toString(),
                    mealPlanSessionId = sessionId,
                    targetKind = TARGET_KIND_PLAN_SHOPPING_LIST,
                    targetName = targetName,
                    sourceKey = "day:${grocery.mealPlanDayId}:grocery:${grocery.id}",
                    sourceVersion = recipeVersionDao.getById(grocery.recipeVersionId)?.version ?: 0,
                    listItemId = null,
                    projectedAt = projectedAt,
                    supersededAt = null,
                )
            },
        )
    }

    private suspend fun rebuildRecipeProjection(
        sessionId: String,
        dayId: String,
        dayIndex: Int,
        recipeVersion: MealPlanRecipeVersionEntity,
    ) {
        val sourcePrefix = "day:$dayId:%"
        val oldTargets = projectionWriteDao.getActiveTargetNamesForSourcePrefix(
            sessionId = sessionId,
            targetKind = TARGET_KIND_RECIPE_LIST,
            sourceKeyPrefix = sourcePrefix,
        )
        oldTargets.forEach { deleteList(it) }
        val projectedAt = System.currentTimeMillis()
        projectionWriteDao.markSupersededForSourcePrefix(
            sessionId = sessionId,
            targetKind = TARGET_KIND_RECIPE_LIST,
            sourceKeyPrefix = sourcePrefix,
            timestamp = projectedAt,
        )
        val targetName = recipeListName(sessionId, dayIndex, recipeVersion.title)
        listNameDao.insert(ListNameEntity(name = targetName))
        val methodSteps = jsonToMethodSteps(recipeVersion.methodStepsJson)
        methodSteps.forEach { step ->
            listItemDao.insert(ListItemEntity(listName = targetName, item = step.text))
        }
        projectionWriteDao.insertAll(
            methodSteps.mapIndexed { index, _ ->
                MealPlanProjectionWriteEntity(
                    id = UUID.randomUUID().toString(),
                    mealPlanSessionId = sessionId,
                    targetKind = TARGET_KIND_RECIPE_LIST,
                    targetName = targetName,
                    sourceKey = "day:$dayId:recipe:${recipeVersion.id}:step:$index",
                    sourceVersion = recipeVersion.version,
                    listItemId = null,
                    projectedAt = projectedAt,
                    supersededAt = null,
                )
            },
        )
    }

    private suspend fun deleteList(name: String) {
        listItemDao.deleteList(name)
        listNameDao.deleteByName(name)
    }

    private suspend fun createSession(conversationId: String): MealPlanSessionEntity {
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
            activeDayIndex = null,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            pendingGenerationStartedAt = null,
            planVersion = 0,
            finalSummaryWritten = false,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            cancelledAt = null,
        )
        sessionDao.insert(entity)
        return entity
    }

    private suspend fun MealPlanSessionEntity.toSnapshot(): MealPlanSnapshot {
        val days = dayDao.getBySession(id)
        return MealPlanSnapshot(
            sessionId = id,
            conversationId = conversationId,
            status = MealPlanSessionStatus.valueOf(status),
            peopleCount = peopleCount,
            daysCount = daysCount,
            dietaryRestrictions = jsonArrayToStringList(dietaryRestrictionsJson),
            proteinPreferences = jsonArrayToStringList(proteinPreferencesJson),
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
                val recipe = day.currentRecipeVersion?.let {
                    recipeVersionDao.getLatestForDay(day.id)?.let { latest ->
                        if (latest.version == it) {
                            RecipeDraft(
                                title = latest.title,
                                servings = latest.servings,
                                ingredients = jsonToIngredients(latest.ingredientsJson),
                                methodSteps = jsonToMethodSteps(latest.methodStepsJson),
                            )
                        } else {
                            null
                        }
                    }
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
                )
            },
        )
    }

    private fun shoppingListName(session: MealPlanSessionEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(session.createdAt))
        return "Meal Plan $date (${session.id.take(8)}) Shopping List"
    }

    private fun recipeListName(sessionId: String, dayIndex: Int, title: String): String =
        "Meal Plan ${sessionId.take(8)} Day ${dayIndex + 1} — $title"

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
    }
}
