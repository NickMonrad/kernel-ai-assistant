package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_plan_sessions",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["status"]),
    ],
)
data class MealPlanSessionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val status: String,
    val peopleCount: Int?,
    val daysCount: Int?,
    val dietaryRestrictionsJson: String = "[]",
    val proteinPreferencesJson: String = "[]",
    val optionalSlotsJson: String = "{}",
    val favouriteRecipeMode: String = "NONE",
    val activeDayIndex: Int? = null,
    val pendingGenerationKind: String? = null,
    val pendingGenerationDayIndex: Int? = null,
    val pendingGenerationStartedAt: Long? = null,
    val planVersion: Int = 0,
    val finalSummaryWritten: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
)
