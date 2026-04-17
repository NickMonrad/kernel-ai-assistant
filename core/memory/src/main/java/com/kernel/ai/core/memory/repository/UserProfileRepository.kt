package com.kernel.ai.core.memory.repository

import android.util.Log
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.UserProfileEntity
import com.kernel.ai.core.memory.profile.UserProfileExtractionUseCase
import com.kernel.ai.core.memory.profile.UserProfileParser
import com.kernel.ai.core.memory.profile.UserProfileYaml
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserProfileRepository @Inject constructor(
    private val dao: UserProfileDao,
    private val extractionUseCase: UserProfileExtractionUseCase,
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
     * Attempts LLM-based extraction (#374 Phase 2b) first; falls back to heuristic regex
     * if the inference engine is not ready. The parsed YAML is logged to logcat (tag KernelAI)
     * so device testing can validate output via `adb logcat -s KernelAI`.
     * Pass an empty string to effectively clear the profile content.
     */
    suspend fun save(text: String) {
        val trimmed = text.take(maxLength)
        val parsed = extractionUseCase.extract(trimmed)
            ?: UserProfileParser.parse(trimmed).also { fallback ->
                Log.d("KernelAI", "Profile regex fallback:\n${fallback.toYaml()}")
            }
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
