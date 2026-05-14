package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_plan_days",
    indices = [Index(value = ["mealPlanSessionId", "dayIndex"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = MealPlanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealPlanSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MealPlanDayEntity(
    @PrimaryKey val id: String,
    val mealPlanSessionId: String,
    val dayIndex: Int,
    val title: String?,
    val summary: String?,
    val proteinTagsJson: String = "[]",
    val status: String = "PENDING",
    val currentRecipeVersion: Int? = null,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
