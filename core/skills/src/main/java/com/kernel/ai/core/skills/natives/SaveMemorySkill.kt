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
                description = "The exact fact or preference to remember, verbatim as the user stated it — NOT a meta-summary. e.g. 'Nick prefers dark mode', never 'User wants to save a preference'."
            )
        ),
        required = listOf("content"),
    )

    override val fullInstructions = """
save_memory: Save an important fact or preference to the user's long-term memory.

Parameters:
- content (required, string): The exact fact verbatim as the user stated it — NOT a meta-description.
  CORRECT: "Nick's cat is called Whiskers"
  WRONG:   "The user wants to remember the name of their cat"

Memory rule: whenever the user says 'remember', 'save', 'note that', 'don't forget',
'keep that in mind', 'save it', 'save that', 'remember that', or asks you to keep
something in mind — you MUST immediately call saveMemory. NEVER output 'Got it',
'I'll remember that', 'I've already saved that', or any confirmation text without
calling the tool first. If the user says 'save it' or 'remember that', infer what
'it'/'that' refers to from recent context.
Do NOT ask what to save — always infer and call the tool.
    """.trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult {
        val content = call.arguments["content"]
            ?: return SkillResult.Failure(name, "Missing 'content' argument")
        return withContext(Dispatchers.IO) {
            try {
                val vector = embeddingEngine.embed(content).takeIf { it.isNotEmpty() }
                    ?: run {
                        Log.w(TAG, "SaveMemorySkill: embedding engine not ready, skipping")
                        return@withContext SkillResult.Failure(name, "Embedding engine not ready")
                    }
                memoryRepository.addCoreMemory(
                    content = content,
                    source = "agent",
                    embeddingVector = vector,
                )
                Log.d(TAG, "SaveMemorySkill: stored core memory — '${content.take(60)}'")
                // Success: action result — LLM narration appropriate
                SkillResult.Success("✓ Saved: \"${content.take(100)}\".")
            } catch (e: Exception) {
                Log.e(TAG, "SaveMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to save memory")
            }
        }
    }
}
