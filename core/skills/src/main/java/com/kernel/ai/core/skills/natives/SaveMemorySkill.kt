package com.kernel.ai.core.skills.natives

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
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
 * Embeds the content synchronously so the vector is immediately available for search.
 */
@Singleton
class SaveMemorySkill @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val embeddingEngine: EmbeddingEngine,
) : Skill {

    override val name = "save_memory"
    override val description =
        "Saves an important fact or preference to the user's long-term core memory " +
            "for future conversations. Use when the user says 'remember', 'save', " +
            "'note that', 'don't forget', 'keep that in mind', 'store this', " +
            "or asks you to save something. Always call this tool — never just say you saved it."
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
        return withContext(Dispatchers.IO) {
            try {
                val vector = embeddingEngine.embed(content).takeIf { it.isNotEmpty() }
                if (vector == null) {
                    Log.w(TAG, "SaveMemorySkill: embedding engine not ready — saving without vector")
                }
                memoryRepository.addCoreMemory(
                    content = content,
                    source = "agent",
                    embeddingVector = vector,
                )
                Log.d(TAG, "SaveMemorySkill: stored core memory (vector=${vector != null}) — '${content.take(60)}'")
                SkillResult.Success("✓ Saved to memory.")
            } catch (e: Exception) {
                Log.e(TAG, "SaveMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to save memory")
            }
        }
    }
}
