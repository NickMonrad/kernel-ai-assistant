package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MealPlanProjectionWriteEntity

@Dao
interface MealPlanProjectionWriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MealPlanProjectionWriteEntity>)

    @Query(
        """
        UPDATE meal_plan_projection_writes
        SET supersededAt = :timestamp
        WHERE mealPlanSessionId = :sessionId
          AND targetKind = :targetKind
          AND supersededAt IS NULL
        """,
    )
    suspend fun markSupersededForTarget(sessionId: String, targetKind: String, timestamp: Long)

    @Query(
        """
        UPDATE meal_plan_projection_writes
        SET supersededAt = :timestamp
        WHERE mealPlanSessionId = :sessionId
          AND supersededAt IS NULL
        """,
    )
    suspend fun markSupersededForSession(sessionId: String, timestamp: Long)

    @Query(
        """
        UPDATE meal_plan_projection_writes
        SET supersededAt = :timestamp
        WHERE mealPlanSessionId = :sessionId
          AND targetKind = :targetKind
          AND sourceKey LIKE :sourceKeyPrefix
          AND supersededAt IS NULL
        """,
    )
    suspend fun markSupersededForSourcePrefix(
        sessionId: String,
        targetKind: String,
        sourceKeyPrefix: String,
        timestamp: Long,
    )

    @Query(
        """
        SELECT DISTINCT targetName FROM meal_plan_projection_writes
        WHERE mealPlanSessionId = :sessionId
          AND targetKind = :targetKind
          AND sourceKey LIKE :sourceKeyPrefix
          AND supersededAt IS NULL
        """,
    )
    suspend fun getActiveTargetNamesForSourcePrefix(
        sessionId: String,
        targetKind: String,
        sourceKeyPrefix: String,
    ): List<String>

    @Query(
        """
        SELECT DISTINCT targetName FROM meal_plan_projection_writes
        WHERE mealPlanSessionId = :sessionId
          AND supersededAt IS NULL
        """,
    )
    suspend fun getActiveTargetNamesForSession(sessionId: String): List<String>
}
