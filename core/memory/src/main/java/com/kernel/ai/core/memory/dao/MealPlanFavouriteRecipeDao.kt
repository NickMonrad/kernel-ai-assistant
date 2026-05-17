package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MealPlanFavouriteRecipeEntity

@Dao
interface MealPlanFavouriteRecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MealPlanFavouriteRecipeEntity)

    @Query("DELETE FROM meal_plan_favourite_recipes WHERE recipeKey = :recipeKey")
    suspend fun delete(recipeKey: String)

    @Query("SELECT * FROM meal_plan_favourite_recipes WHERE recipeKey = :recipeKey LIMIT 1")
    suspend fun getByKey(recipeKey: String): MealPlanFavouriteRecipeEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM meal_plan_favourite_recipes WHERE recipeKey = :recipeKey)")
    suspend fun exists(recipeKey: String): Boolean

    @Query("SELECT recipeKey FROM meal_plan_favourite_recipes WHERE recipeKey IN (:recipeKeys)")
    suspend fun getExistingKeys(recipeKeys: List<String>): List<String>

    @Query("SELECT * FROM meal_plan_favourite_recipes ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MealPlanFavouriteRecipeEntity>
}
