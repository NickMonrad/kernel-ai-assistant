package com.kernel.ai.core.memory.mealplan

import org.json.JSONArray
import org.json.JSONObject

enum class MealPlanSessionStatus {
    COLLECTING_REQUIRED_SLOTS,
    PLAN_REVIEW,
    RECIPES_IN_PROGRESS,
    AWAITING_USER_EDIT_OR_RECOVERY,
    COMPLETED,
    CANCELLED,
}

enum class MealPlanDayStatus {
    PENDING,
    DRAFTED,
    PERSISTED,
    FAILED,
}

enum class PendingGenerationKind {
    PLAN,
    RECIPE,
    REPLACEMENT,
}

enum class GroceryNormalizationStatus {
    OPAQUE,
    NORMALIZED,
    FAILED,
}

data class MealPlanDraftDay(
    val dayIndex: Int,
    val title: String,
    val summary: String?,
    val proteinTags: List<String> = emptyList(),
)

data class MealPlanDraft(
    val days: List<MealPlanDraftDay>,
)

data class RecentMealHistoryEntry(
    val title: String,
    val summary: String?,
    val proteinTags: List<String> = emptyList(),
)

data class RecipeDraftIngredient(
    val originalText: String,
    val amount: String?,
    val unit: String?,
    val item: String?,
    val note: String?,
)

data class RecipeDraftMethodStep(
    val stepNumber: Int,
    val text: String,
)

data class RecipeDraft(
    val title: String,
    val servings: Int,
    val ingredients: List<RecipeDraftIngredient>,
    val methodSteps: List<RecipeDraftMethodStep>,
)

data class CanonicalGroceryItem(
    val displayText: String,
    val originalText: String,
    val quantity: String? = null,
    val unit: String? = null,
    val ingredientName: String? = null,
    val note: String? = null,
    val normalizationStatus: GroceryNormalizationStatus,
    val mergeKey: String? = null,
)

data class MealPlanSnapshot(
    val sessionId: String,
    val conversationId: String,
    val displayName: String,
    val status: MealPlanSessionStatus,
    val peopleCount: Int?,
    val daysCount: Int?,
    val dietaryRestrictions: List<String>,
    val proteinPreferences: List<String>,
    val activeDayIndex: Int?,
    val pendingGenerationKind: PendingGenerationKind?,
    val pendingGenerationDayIndex: Int?,
    val planVersion: Int,
    val finalSummaryWritten: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val cancelledAt: Long?,
    val days: List<MealPlanSnapshotDay>,
)

data class MealPlanSnapshotDay(
    val id: String,
    val dayIndex: Int,
    val title: String?,
    val summary: String?,
    val proteinTags: List<String>,
    val status: MealPlanDayStatus,
    val currentRecipeVersion: Int?,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val currentRecipe: RecipeDraft?,
)

fun List<String>.toJsonArrayString(): String = JSONArray(this).toString()

fun jsonArrayToStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { index -> arr.optString(index).trim() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

fun mapToJsonObjectString(map: Map<String, String>): String = JSONObject(map).toString()
