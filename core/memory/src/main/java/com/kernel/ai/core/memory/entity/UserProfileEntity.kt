package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton user profile injected into every LLM system prompt.
 *
 * Only one row ever exists (id = 1). The [profileText] is a free-form narrative
 * block written/edited by the user. The [structuredJson] column holds a
 * [com.kernel.ai.core.memory.profile.UserProfileYaml] serialised as JSON,
 * derived from the free-text by heuristic parsing (and optionally refined by LLM).
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val profileText: String,
    val updatedAt: Long,
    /** JSON-serialised UserProfileYaml; null until first parse. */
    val structuredJson: String? = null,
)
