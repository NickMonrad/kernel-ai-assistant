package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists the active meal-planner session state for a single conversation.
 *
 * Scoped by [conversationId] — one session per conversation.
 * Structured JSON fields (dietaryRestrictionsJson, proteinPreferencesJson, highLevelPlanJson)
 * future-proof the seam toward the generic artifact system (#235).
 */
@Entity(tableName = "meal_plan_sessions")
data class MealPlanSessionEntity(
    @PrimaryKey val conversationId: String,

    /** Current flow stage. Values: collecting_preferences, high_level_plan_ready,
     *  generating_recipes, completed. */
    val status: String = "collecting_preferences",

    /** Number of people the plan is for. */
    val peopleCount: Int? = null,

    /** Number of days in the plan. */
    val days: Int? = null,

    /** Compact JSON array of dietary restrictions, e.g. ["vegetarian","gluten-free"]. */
    val dietaryRestrictionsJson: String = "[]",

    /** Compact JSON array of protein preferences, e.g. ["chicken","fish"]. */
    val proteinPreferencesJson: String = "[]",

    /** Assistant-generated high-level plan text (one-line summary per day). */
    val highLevelPlanJson: String? = null,

    /** Index of the day currently being detailed (0-based). */
    val currentDayIndex: Int? = null,

    /** Last update timestamp. */
    val updatedAt: Long = System.currentTimeMillis(),
)
