package com.kernel.ai.core.skills.natives

import android.util.Log
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Skill that saves an important fact or preference to the user's long-term core memory.
 * Persists via [MemoryRepository.addCoreMemory] without requiring an embedding vector
 * (the repository accepts null — the background embedding pipeline handles it asynchronously).
 */
@Singleton
class SaveMemorySkill @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : Skill {

    override val name = "save_memory"
    override val description =
        "Saves an important fact or preference to the user's long-term core memory " +
            "for future conversations."
    override val schema = SkillSchema(
        parameters = mapOf(
            "content" to SkillParameter(
                type = "string",
                description = "The fact or preference to remember, written as a clear statement."
            )
        ),
        required = listOf("content"),
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val content = call.arguments["content"]
            ?: return SkillResult.Failure(name, "Missing 'content' argument")
        return withContext(Dispatchers.Default) {
            try {
                memoryRepository.addCoreMemory(
                    content = content,
                    source = "agent",
                    embeddingVector = null,
                )
                Log.d(TAG, "SaveMemorySkill: stored core memory — '${content.take(60)}'")
                SkillResult.Success("Got it — I'll remember that.")
            } catch (e: Exception) {
                Log.e(TAG, "SaveMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to save memory")
            }
        }
    }
}
