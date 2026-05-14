package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.MealPlanDayEntity

@Dao
interface MealPlanDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MealPlanDayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MealPlanDayEntity>)

    @Update
    suspend fun update(entity: MealPlanDayEntity)

    @Query("SELECT * FROM meal_plan_days WHERE mealPlanSessionId = :sessionId ORDER BY dayIndex ASC")
    suspend fun getBySession(sessionId: String): List<MealPlanDayEntity>

    @Query("SELECT * FROM meal_plan_days WHERE mealPlanSessionId = :sessionId AND dayIndex = :dayIndex LIMIT 1")
    suspend fun getBySessionAndIndex(sessionId: String, dayIndex: Int): MealPlanDayEntity?

    @Query("DELETE FROM meal_plan_days WHERE mealPlanSessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
