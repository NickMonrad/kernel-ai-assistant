package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Persists the history of quick-action commands executed via FunctionGemma.
 *
 * Each row represents a single user query → FunctionGemma inference → tool execution cycle.
 */
@Entity(tableName = "quick_actions")
data class QuickActionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** The raw text the user typed (e.g. "turn on flashlight"). */
    val userQuery: String,
    /** Name of the skill/tool that was invoked, if any. Null when no tool was triggered. */
    val skillName: String? = null,
    /** The final response shown to the user. */
    val resultText: String,
    /** Whether the action completed successfully. */
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
