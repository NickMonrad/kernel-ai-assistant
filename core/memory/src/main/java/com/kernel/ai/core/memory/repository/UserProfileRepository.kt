package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.UserProfileEntity
import com.kernel.ai.core.memory.profile.UserProfileParser
import com.kernel.ai.core.memory.profile.UserProfileYaml
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

    /** Observe the structured profile (null when not parsed or empty). */
    fun observeStructured(): Flow<UserProfileYaml?> = dao.observe().map { entity ->
        entity?.structuredJson?.let { UserProfileYaml.fromJson(it) }
    }

    /** Get the current profile text once (empty string when not set). */
    suspend fun get(): String = dao.get()?.profileText ?: ""

    /** Get the structured profile (null when not yet parsed). */
    suspend fun getStructured(): UserProfileYaml? =
        dao.get()?.structuredJson?.let { UserProfileYaml.fromJson(it) }

    /**
     * Save [text] as the user profile. Trims to [maxLength] if needed.
     * Automatically runs heuristic parsing to populate structured fields.
     * Pass an empty string to effectively clear the profile content.
     */
    suspend fun save(text: String) {
        val trimmed = text.take(maxLength)
        val parsed = UserProfileParser.parse(trimmed)
        val json = if (parsed.isEmpty()) null else parsed.toJson()
        dao.upsert(
            UserProfileEntity(
                profileText = trimmed,
                updatedAt = System.currentTimeMillis(),
                structuredJson = json,
            )
        )
    }

    /** Remove the profile entirely. */
    suspend fun clear() = dao.clear()
}
