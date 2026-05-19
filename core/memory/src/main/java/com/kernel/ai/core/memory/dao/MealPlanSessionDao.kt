package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity

data class RecentTerminalMealHistoryRow(
    val title: String,
    val summary: String?,
    val proteinTagsJson: String,
)

@Dao
interface MealPlanSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MealPlanSessionEntity)

    @Update
    suspend fun update(entity: MealPlanSessionEntity)

    @Query("DELETE FROM meal_plan_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

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

    @Query("SELECT MAX(displayCode) FROM meal_plan_sessions")
    suspend fun getMaxDisplayCode(): Int?

    @Query(
        """
        SELECT d.title AS title, d.summary AS summary, d.proteinTagsJson AS proteinTagsJson
        FROM meal_plan_days d
        JOIN meal_plan_sessions s ON s.id = d.mealPlanSessionId
        WHERE s.status IN ('COMPLETED', 'CANCELLED')
          AND d.title IS NOT NULL
          AND TRIM(d.title) != ''
        ORDER BY COALESCE(s.completedAt, s.cancelledAt, s.updatedAt) DESC, d.dayIndex ASC, d.id ASC
        LIMIT :limit
        """,
    )
    suspend fun getRecentTerminalMealHistory(limit: Int): List<RecentTerminalMealHistoryRow>

    @Query(
        """
        SELECT * FROM meal_plan_sessions
        WHERE status = 'COMPLETED'
          AND finalSummaryWritten = 0
          AND completedAt IS NOT NULL
        ORDER BY completedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getCompletedWithoutSummary(limit: Int): List<MealPlanSessionEntity>

    @Query(
        """
        SELECT * FROM meal_plan_sessions
        WHERE (
            status = 'COMPLETED'
            AND completedAt IS NOT NULL
            AND (
                (finalSummaryWritten = 1 AND completedAt <= :completedBefore)
                OR completedAt <= :completedWithoutSummaryBefore
            )
        )
        OR (
            status = 'CANCELLED'
            AND cancelledAt IS NOT NULL
            AND cancelledAt <= :cancelledBefore
        )
        ORDER BY updatedAt ASC
        """,
    )
    suspend fun getPrunableTerminalSessions(
        completedBefore: Long,
        completedWithoutSummaryBefore: Long,
        cancelledBefore: Long,
    ): List<MealPlanSessionEntity>
}
