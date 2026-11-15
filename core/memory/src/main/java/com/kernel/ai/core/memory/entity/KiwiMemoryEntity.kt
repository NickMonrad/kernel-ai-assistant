package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores NZ/Jandal corpus entries (source = "jandal_persona") in a dedicated table,
 * separate from user-created [CoreMemoryEntity] facts.
 *
 * Separation ensures [NzTruthSeedingService] can safely drop and recreate the kiwi
 * vec table on every seed-guard bump without touching the user's personal memories.
 */
@Entity(tableName = "kiwi_memories", indices = [Index("id", unique = true)])
data class KiwiMemoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,  // e.g. "nz_009"
    val content: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
    val source: String,  // "jandal_persona"
    val vectorized: Boolean = false,
    val category: String = "agent_identity",
    val term: String = "",
    val definition: String = "",
    val triggerContext: String = "",
    /** Vibe metadata (1 subtle to 5 high-energy); per-vibe cutoffs await calibration. */
    val vibeLevel: Int = 1,
    val metadataJson: String = "{}",
)
