package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "core_memories", indices = [Index("id", unique = true)])
data class CoreMemoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,  // UUID
    val content: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
    val source: String,  // "user" or "dreaming"
)
