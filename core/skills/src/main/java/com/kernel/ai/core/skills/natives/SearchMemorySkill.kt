package com.kernel.ai.core.skills.natives

import android.util.Log
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Skill that searches the user's saved memories (core and episodic) as well as their
 * cross-conversation message history for content semantically similar to a query.
 *
 * Three result sources are merged:
 * - **Core memories**: permanent facts saved explicitly by the user via `save_memory`.
 * - **Episodic memories**: distilled summaries of past conversations.
 * - **Message history**: individual messages from past conversations.
 *
 * Two usage modes for message history:
 * - No [conversationId]: cross-conversation search across all past messages.
 * - With [conversationId]: scoped search within a specific conversation, useful for
 *   the summary-to-detail path where the model has an episodic summary and wants the
 *   underlying messages.
 */
@Singleton
class SearchMemorySkill @Inject constructor(
    private val ragRepository: RagRepository,
) : Skill {

    override val name = "search_memory"
    override val description =
        "Search your saved memories and message history for information about a topic. Use when " +
            "the user asks what you remember about something, wants to recall a fact they told you, " +
            "asks you to recall a past conversation, or asks 'what did we discuss about X'. " +
            "Searches both explicitly saved memories (core/episodic) and raw message history. " +
            "Optionally scope to a specific conversationId from an episodic summary for detail retrieval."
    override val schema = SkillSchema(
        parameters = mapOf(
            "query" to SkillParameter(
                type = "string",
                description = "The topic or phrase to search for in saved memories and past messages. " +
                    "Replace first-person pronouns with the user's actual name if known from their profile " +
                    "(e.g. 'my ancestor' → 'Nick\\'s ancestor', 'my job' → 'Nick\\'s job')."
            ),
            "conversationId" to SkillParameter(
                type = "string",
                description = "Optional. Restrict message-history search to this conversation ID (from an episodic summary)."
            ),
            "topK" to SkillParameter(
                type = "integer",
                description = "Maximum number of results to return per source. Defaults to 5."
            ),
        ),
        required = listOf("query"),
    )

    override val examples = listOf(
        "User asks 'what do you remember about my project?' → Call: <|tool_call>call:search_memory{query:<|\"project\"|>}<tool_call|>",
        "User asks 'what did we decide last week?' → Call: <|tool_call>call:search_memory{query:<|\"decision last week\"|>}<tool_call|>",
        "Drill into an episodic summary tagged conversation:abc-123 → Call: <|tool_call>call:search_memory{query:<|\"topic\"|>,conversationId:<|\"abc-123\"|>}<tool_call|>",
        "User asks 'what do you remember about my family?' → Call: <|tool_call>call:search_memory{query:<|\"family\"|>}<tool_call|>",
    )

    override val fullInstructions = """
search_memory: Search the user's long-term memory for facts and preferences.

Parameters:
- query (required, string): A natural language query describing what to look for.

Recall rule: ALWAYS call search_memory BEFORE answering any question that might depend
on the user's name, preferences, prior statements, or user-specific facts.
If the user references something 'you know' about them, or says 'remember when I told you…',
call search_memory first. Do NOT skip this step even if you think you know the answer.
After calling search_memory, incorporate its result into your reply naturally.

Examples:
  <|tool_call>call:search_memory{query:<|"|>user's preferred language<|"|>}<tool_call|>
  <|tool_call>call:search_memory{query:<|"|>workout routine preferences<|"|>}<tool_call|>
    """.trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult {
        val query = call.arguments["query"]
            ?: return SkillResult.Failure(name, "Missing 'query' argument")
        val conversationId = call.arguments["conversationId"]?.takeIf { it.isNotBlank() }
        val topK = call.arguments["topK"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        return withContext(Dispatchers.Default) {
            try {
                val messageResults = runCatching {
                    ragRepository.searchMessages(query, conversationId, topK)
                }.getOrElse { e ->
                    Log.w(TAG, "searchMessages failed: ${e.message}", e)
                    emptyList()
                }
                val memoryResults = runCatching {
                    ragRepository.searchCoreAndEpisodic(query, topK)
                }.getOrElse { e ->
                    Log.w(TAG, "searchCoreAndEpisodic failed: ${e.message}", e)
                    emptyList()
                }
                Log.d(
                    TAG,
                    "SearchMemorySkill: query='${query.take(60)}' conversationId=$conversationId " +
                        "→ ${memoryResults.size} memory results, ${messageResults.size} message results",
                )

                if (memoryResults.isEmpty() && messageResults.isEmpty()) {
                    return@withContext SkillResult.Success("No memories found matching '$query'.")
                }

                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                val sb = StringBuilder("Here's what I found in memory:\n\n")
                var index = 1

                // Explicitly saved facts first — these are the most direct answer to "what do you remember".
                memoryResults.forEach { result ->
                    val sourceTag = if (result.source == "core") "Core Memory" else "Episodic Memory"
                    val dateStr = if (result.lastAccessedAt > 0L) fmt.format(Date(result.lastAccessedAt)) else "date unknown"
                    sb.appendLine("${index++}. [$sourceTag — $dateStr]")
                    sb.appendLine("   ${result.content.take(300)}")
                }

                // Message history results follow.
                if (messageResults.isNotEmpty()) {
                    if (memoryResults.isNotEmpty()) sb.appendLine()
                    messageResults.forEach { result ->
                        val role = if (result.role == "user") "You" else "Me"
                        val date = fmt.format(Date(result.timestamp))
                        sb.appendLine("${index++}. [Message — $date — conversation:${result.conversationId.take(8)}]")
                        sb.appendLine("   $role: ${result.content.take(300)}")
                    }
                }

                SkillResult.Success(sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "SearchMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to search memories")
            }
        }
    }
}
