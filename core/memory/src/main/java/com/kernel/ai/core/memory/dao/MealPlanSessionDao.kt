package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity

@Dao
interface MealPlanSessionDao {

    @Query("SELECT * FROM meal_plan_sessions WHERE conversationId = :conversationId")
    suspend fun getByConversationId(conversationId: String): MealPlanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: MealPlanSessionEntity)

    @Query("DELETE FROM meal_plan_sessions WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    @Query("DELETE FROM meal_plan_sessions")
    suspend fun deleteAll()
}
