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
    val vectorized: Boolean = false,
    /** Separates user facts from agent identity (NZ knowledge). Default "user" for backward compat. */
    val category: String = "user",
    // ── NZ truth structured fields (default empty for user memories) ──────────
    /** Short display name for this truth (e.g. "Jandal", "Pavlova"). */
    val term: String = "",
    /** Human-readable explanation injected into the prompt. */
    val definition: String = "",
    /** Hint describing when this truth should surface. */
    val triggerContext: String = "",
    /** 1 (subtle/serious) → 5 (high-energy/chaotic). Controls retrieval distance threshold. */
    val vibeLevel: Int = 1,
    /** JSON string of metadata tags/era (e.g. {"tags":["food"],"era":"timeless"}). */
    val metadataJson: String = "{}",
)
