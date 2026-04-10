package com.kernel.ai.core.memory.repository

data class MemorySearchResult(
    val id: String,
    val content: String,
    val source: String,  // "episodic" or "core"
    val score: Float,
)
