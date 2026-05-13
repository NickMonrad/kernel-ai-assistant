package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class ConversionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "input_amount") val inputAmount: String,
    @ColumnInfo(name = "from_label") val fromLabel: String,
    @ColumnInfo(name = "to_label") val toLabel: String,
    @ColumnInfo(name = "output_amount") val outputAmount: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
