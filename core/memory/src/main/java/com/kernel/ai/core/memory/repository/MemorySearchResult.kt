package com.kernel.ai.core.memory.repository

data class MemorySearchResult(
    val id: String,
    val content: String,
    val source: String,       // "episodic" or "core"
    val score: Float,
    val lastAccessedAt: Long = 0L, // populated for core memories; used as tiebreaker when truncating
    val conversationId: String? = null, // populated for episodic memories; used for summary-to-detail retrieval
    // NZ truth structured fields — populated for agent_identity memories, empty otherwise
    val term: String = "",
    val definition: String = "",
)
