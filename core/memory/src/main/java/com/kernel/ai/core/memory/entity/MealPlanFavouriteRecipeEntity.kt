package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meal_plan_favourite_recipes")
data class MealPlanFavouriteRecipeEntity(
    @PrimaryKey val recipeKey: String,
    val title: String,
    val summary: String?,
    val proteinTagsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
)
