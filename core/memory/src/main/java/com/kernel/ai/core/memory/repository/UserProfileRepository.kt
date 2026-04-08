package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserProfileRepository @Inject constructor(
    private val dao: UserProfileDao,
) {
    /** Max character length — roughly 500 tokens. Enforced on save. */
    val maxLength = 2000

    /** Observe the current profile text (empty string when not set). */
    fun observe(): Flow<String> = dao.observe().map { it?.profileText ?: "" }

    /** Get the current profile text once (empty string when not set). */
    suspend fun get(): String = dao.get()?.profileText ?: ""

    /**
     * Save [text] as the user profile. Trims to [maxLength] if needed.
     * Pass an empty string to effectively clear the profile content.
     */
    suspend fun save(text: String) {
        val trimmed = text.take(maxLength)
        dao.upsert(
            UserProfileEntity(
                profileText = trimmed,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Remove the profile entirely. */
    suspend fun clear() = dao.clear()
}
