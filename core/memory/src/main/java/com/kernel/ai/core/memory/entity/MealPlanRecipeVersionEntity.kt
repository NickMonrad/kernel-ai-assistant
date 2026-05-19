package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_plan_recipe_versions",
    indices = [Index(value = ["mealPlanDayId", "version"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = MealPlanDayEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealPlanDayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MealPlanRecipeVersionEntity(
    @PrimaryKey val id: String,
    val mealPlanSessionId: String,
    val mealPlanDayId: String,
    val version: Int,
    val recipeKey: String,
    val title: String,
    val servings: Int,
    val ingredientsJson: String,
    val methodStepsJson: String,
    val rawModelJson: String,
    val createdAt: Long,
)
