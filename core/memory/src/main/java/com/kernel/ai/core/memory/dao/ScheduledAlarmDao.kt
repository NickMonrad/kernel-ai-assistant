package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity

@Dao
interface ScheduledAlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: ScheduledAlarmEntity)

    @Query("SELECT * FROM scheduled_alarms WHERE fired = 0 AND triggerAtMillis > :nowMillis")
    suspend fun getUnfiredFuture(nowMillis: Long): List<ScheduledAlarmEntity>

    @Query("UPDATE scheduled_alarms SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: String)

    @Query("DELETE FROM scheduled_alarms WHERE id = :id")
    suspend fun delete(id: String)
}
