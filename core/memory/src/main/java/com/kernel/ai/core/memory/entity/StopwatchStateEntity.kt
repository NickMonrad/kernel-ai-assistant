package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stopwatch_state")
data class StopwatchStateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "accumulated_elapsed_ms") val accumulatedElapsedMs: Long,
    @ColumnInfo(name = "running_since_elapsed_realtime_ms") val runningSinceElapsedRealtimeMs: Long?,
    @ColumnInfo(name = "running_since_wall_clock_ms") val runningSinceWallClockMs: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
