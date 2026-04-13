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
 * Skill that searches the user's cross-conversation message history for content
 * semantically similar to a query.
 *
 * Two usage modes:
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
        "Search your saved message history for information about a topic. Use when the user " +
            "asks what you remember about something, asks you to recall a past conversation, " +
            "or asks 'what did we discuss about X'. Optionally scope to a specific " +
            "conversationId from an episodic summary for detail retrieval."
    override val schema = SkillSchema(
        parameters = mapOf(
            "query" to SkillParameter(
                type = "string",
                description = "The topic or phrase to search for in past messages."
            ),
            "conversationId" to SkillParameter(
                type = "string",
                description = "Optional. Restrict search to this conversation ID (from an episodic summary)."
            ),
            "topK" to SkillParameter(
                type = "integer",
                description = "Maximum number of results to return. Defaults to 5."
            ),
        ),
        required = listOf("query"),
    )

    override val examples = listOf(
        "User asks 'what do you remember about my project?' → Call: <|tool_call>call:search_memory{query:<|\"project\"|>}<tool_call|>",
        "User asks 'what did we decide last week?' → Call: <|tool_call>call:search_memory{query:<|\"decision last week\"|>}<tool_call|>",
        "Drill into an episodic summary tagged conversation:abc-123 → Call: <|tool_call>call:search_memory{query:<|\"topic\"|>,conversationId:<|\"abc-123\"|>}<tool_call|>",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val query = call.arguments["query"]
            ?: return SkillResult.Failure(name, "Missing 'query' argument")
        val conversationId = call.arguments["conversationId"]?.takeIf { it.isNotBlank() }
        val topK = call.arguments["topK"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        return withContext(Dispatchers.Default) {
            try {
                val results = ragRepository.searchMessages(query, conversationId, topK)
                Log.d(TAG, "SearchMemorySkill: query='${query.take(60)}' conversationId=$conversationId → ${results.size} results")

                if (results.isEmpty()) {
                    return@withContext SkillResult.Success("No memories found matching '$query'.")
                }

                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                val sb = StringBuilder("Here's what I found in our past conversations:\n\n")
                results.forEachIndexed { i, result ->
                    val role = if (result.role == "user") "You" else "Me"
                    val date = fmt.format(Date(result.timestamp))
                    sb.appendLine("${i + 1}. [$date — conversation:${result.conversationId.take(8)}]")
                    sb.appendLine("   $role: ${result.content.take(300)}")
                }
                SkillResult.Success(sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "SearchMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to search memories")
            }
        }
    }
}
