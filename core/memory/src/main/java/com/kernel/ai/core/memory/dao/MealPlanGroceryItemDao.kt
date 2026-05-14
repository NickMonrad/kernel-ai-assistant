package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MealPlanGroceryItemEntity

@Dao
interface MealPlanGroceryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MealPlanGroceryItemEntity>)

    @Query("SELECT * FROM meal_plan_grocery_items WHERE recipeVersionId = :recipeVersionId ORDER BY ingredientIndex ASC")
    suspend fun getByRecipeVersion(recipeVersionId: String): List<MealPlanGroceryItemEntity>

    @Query(
        """
        SELECT g.*
        FROM meal_plan_grocery_items g
        INNER JOIN meal_plan_days d ON d.id = g.mealPlanDayId
        WHERE g.mealPlanSessionId = :sessionId
          AND d.currentRecipeVersion IS NOT NULL
          AND g.recipeVersionId IN (
              SELECT rv.id FROM meal_plan_recipe_versions rv
              INNER JOIN meal_plan_days rd ON rd.id = rv.mealPlanDayId
              WHERE rd.mealPlanSessionId = :sessionId
                AND rd.currentRecipeVersion = rv.version
          )
        ORDER BY d.dayIndex ASC, g.ingredientIndex ASC
        """,
    )
    suspend fun getCurrentForSession(sessionId: String): List<MealPlanGroceryItemEntity>

    @Query("DELETE FROM meal_plan_grocery_items WHERE recipeVersionId = :recipeVersionId")
    suspend fun deleteByRecipeVersion(recipeVersionId: String)
}
