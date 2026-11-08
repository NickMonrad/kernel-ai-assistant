package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stopwatch_laps",
    foreignKeys = [
        ForeignKey(
            entity = StopwatchStateEntity::class,
            parentColumns = ["id"],
            childColumns = ["stopwatch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["stopwatch_id"]),
        Index(value = ["stopwatch_id", "lap_number"], unique = true),
    ],
)
data class StopwatchLapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "stopwatch_id") val stopwatchId: String,
    @ColumnInfo(name = "lap_number") val lapNumber: Int,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "split_ms") val splitMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
