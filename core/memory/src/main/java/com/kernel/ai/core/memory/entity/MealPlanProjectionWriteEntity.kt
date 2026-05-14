package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_plan_projection_writes",
    indices = [
        Index(value = ["mealPlanSessionId"]),
        Index(value = ["targetKind", "targetName", "sourceKey", "sourceVersion"], unique = true),
    ],
)
data class MealPlanProjectionWriteEntity(
    @PrimaryKey val id: String,
    val mealPlanSessionId: String,
    val targetKind: String,
    val targetName: String,
    val sourceKey: String,
    val sourceVersion: Int,
    val listItemId: Long? = null,
    val projectedAt: Long,
    val supersededAt: Long? = null,
)
