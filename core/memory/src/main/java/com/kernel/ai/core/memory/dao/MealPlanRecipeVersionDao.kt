package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MealPlanRecipeVersionEntity

@Dao
interface MealPlanRecipeVersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MealPlanRecipeVersionEntity)

    @Query("SELECT * FROM meal_plan_recipe_versions WHERE mealPlanDayId = :dayId ORDER BY version DESC LIMIT 1")
    suspend fun getLatestForDay(dayId: String): MealPlanRecipeVersionEntity?

    @Query("SELECT * FROM meal_plan_recipe_versions WHERE id = :recipeVersionId LIMIT 1")
    suspend fun getById(recipeVersionId: String): MealPlanRecipeVersionEntity?
}
