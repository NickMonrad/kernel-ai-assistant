package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "meal_plan_grocery_items",
    indices = [Index(value = ["recipeVersionId", "ingredientIndex"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = MealPlanRecipeVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MealPlanGroceryItemEntity(
    @PrimaryKey val id: String,
    val mealPlanSessionId: String,
    val mealPlanDayId: String,
    val recipeVersionId: String,
    val ingredientIndex: Int,
    val displayText: String,
    val originalText: String,
    val quantity: String? = null,
    val unit: String? = null,
    val ingredientName: String? = null,
    val note: String? = null,
    val normalizationStatus: String,
    val mergeKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
