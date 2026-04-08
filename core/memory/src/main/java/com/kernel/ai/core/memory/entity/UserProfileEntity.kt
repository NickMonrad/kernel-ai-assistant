package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton user profile injected into every LLM system prompt.
 *
 * Only one row ever exists (id = 1). The [profileText] is a free-form narrative
 * block written/edited by the user (or eventually auto-updated by the Dreaming Engine).
 * Kept under ~500 tokens (~2,000 chars) so it fits comfortably in the context budget.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val profileText: String,
    val updatedAt: Long,
)
