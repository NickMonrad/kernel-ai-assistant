package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "episodic_memories", indices = [Index("id", unique = true)])
data class EpisodicMemoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,  // UUID
    val conversationId: String,
    val content: String,
    val createdAt: Long,
    val accessCount: Int = 0,
    // NOTE: defaults to 0 (not createdAt) due to SQLite migration constraint
    val lastAccessedAt: Long = 0,
)
