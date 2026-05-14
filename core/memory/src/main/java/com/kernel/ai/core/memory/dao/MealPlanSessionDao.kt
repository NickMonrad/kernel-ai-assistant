package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity

@Dao
interface MealPlanSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MealPlanSessionEntity)

    @Update
    suspend fun update(entity: MealPlanSessionEntity)

    @Query("SELECT * FROM meal_plan_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): MealPlanSessionEntity?

    @Query(
        """
        SELECT * FROM meal_plan_sessions
        WHERE conversationId = :conversationId
          AND status NOT IN ('COMPLETED', 'CANCELLED')
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getActiveByConversation(conversationId: String): MealPlanSessionEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM meal_plan_sessions
            WHERE conversationId = :conversationId
              AND status NOT IN ('COMPLETED', 'CANCELLED')
        )
        """,
    )
    suspend fun hasActiveForConversation(conversationId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM meal_plan_sessions
            WHERE conversationId = :conversationId
        )
        """,
    )
    suspend fun hasAnyForConversation(conversationId: String): Boolean
}
