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
                description = "The fact or preference to remember, written in third person using the user's name. " +
                    "Convert first-person pronouns: 'I' → user's name, 'my' → user's name + 's', 'I'm' → user's name + ' is'. " +
                    "e.g. if the user says 'I like dark mode', store 'Nick likes dark mode'. " +
                    "NEVER store as 'I like dark mode' or use a meta-summary like 'User wants to save a preference'."
            )
        ),
        required = listOf("content"),
    )

    override val fullInstructions = """
save_memory: Save an important fact or preference to the user's long-term memory.

Parameters:
- content (required, string): The fact in THIRD PERSON using the user's actual name from context.
  Convert first-person before storing:
    "I like dark mode"           → "Nick likes dark mode"
    "my mum's name is Susan"     → "Nick's mum's name is Susan"
    "I'm vegetarian"             → "Nick is vegetarian"
    "I prefer tea over coffee"   → "Nick prefers tea over coffee"
  WRONG: storing "I like dark mode" or "my mum's name is Susan" verbatim.
  WRONG: meta-descriptions like "The user wants to remember their preference".

Memory rule: whenever the user says 'remember', 'note that', 'don't forget',
'keep that in mind', 'remember that', or asks you to keep something in mind —
you MUST immediately call saveMemory. NEVER output 'Got it', 'I'll remember that',
or any confirmation text without calling the tool first.

If 'remember it', 'remember this', or 'remember that' has no clear personal fact
from the user's CURRENT message, ask: "What would you like me to remember?" —
do NOT infer from system instructions or tool-use format descriptions.

NEVER use save_memory to add items to a shopping list, grocery list, to-do list, or
any other named list — use run_intent with add_to_list (single item) or
bulk_add_to_list (two or more items) for that instead.
    """.trimIndent()

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
                    embeddingVector = vector ?: floatArrayOf(),
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
