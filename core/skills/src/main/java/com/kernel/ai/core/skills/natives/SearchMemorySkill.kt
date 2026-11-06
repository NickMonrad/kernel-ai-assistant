package com.kernel.ai.core.skills.natives

import android.util.Log
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.MemorySearchResult
import com.kernel.ai.core.memory.repository.UserProfileRepository
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
private val PERSONAL_PREFERENCE_CUE = Regex(
    """\b(favourite|favorite|prefer|preferred|preference|preferences)\b""",
    RegexOption.IGNORE_CASE,
)

private val PERSONAL_POSSESSIVE_REFERENCE = Regex("""\b(?:my|mine|our|ours)\b""")
private val PERSONAL_SELF_AS_SUBJECT = Regex(
    """\b(?:(?:what\s+)?(?:did|have)\s+(?:i|we)\s+(?:tell|told|say|said|ask|asked|mention|mentioned|discuss|discussed|talk(?:\s+about)?|talked(?:\s+about)?)|(?:i|we)\s+(?:told|said|asked|mentioned|discussed|talked(?:\s+about)?))\b""",
)
private val ABOUT_SELF_REFERENCE = Regex("""\babout\s+(?:me|myself|us|ourselves)\b""")
private val CURRENT_CONVERSATION_RECALL_PHRASE = Regex(
    """\bwhat\s+(?:did\s+(?:i|we)\s+(?:say|ask|tell|mention|discuss|talk(?:\s+about)?)|have\s+(?:i|we)\s+(?:said|asked|told|mentioned|discussed|talked(?:\s+about)?))\b""",
)
private val CURRENT_CONVERSATION_WORDS = Regex("""[\p{L}\d]+""")
private val CURRENT_CONVERSATION_FILLER_WORDS = setOf(
    "about", "above", "before", "earlier", "in", "just", "now", "previously", "the", "this",
    "turn", "chat", "conversation", "you", "i", "we", "me", "us", "to", "that", "it", "today",
    "yesterday",
)
private val PERSONAL_RECALL_PHRASES = listOf(
    "what do you remember",
    "what did i tell",
    "what have i told",
    "what did i say",
    "what have i said",
    "do you remember",
    "can you recall",
    "what did we discuss",
    "what did we talk about",
)
private val QUESTION_LIKE_MEMORY_MESSAGE = Regex(
    """^(what|which|who|when|where|why|how|do|does|did|is|are|can|could|would|have|has)\b""",
    RegexOption.IGNORE_CASE,
)

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
    private val userProfileRepository: UserProfileRepository,
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
        "User asks 'what do you remember about my project?' → searchMemory(query=\"project\")",
        "User asks 'what did we decide last week?' → searchMemory(query=\"decision last week\")",
        "Drill into episodic summary → searchMemory(query=\"topic\") with conversationId if available",
        "User asks 'what do you remember about my family?' → searchMemory(query=\"family\")",
    )

    override val fullInstructions = """
search_memory: Search the user's long-term memory for facts and preferences.

Parameters:
- query (required, string): A natural language query describing what to look for.

Recall rule: ALWAYS call searchMemory BEFORE answering any question that might depend
on the user's name, preferences, prior statements, or user-specific facts.
If the user references something 'you know' about them, or says 'remember when I told you…',
call searchMemory first. Do NOT skip this step even if you think you know the answer.
After calling searchMemory, answer using only relevant facts in its filtered results. Do not
add details from unrelated memory/persona context or prior knowledge. For generic "what do you
remember about me" queries, present the returned saved user facts as a concise list.
    """.trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult {
        val query = call.arguments["query"]
            ?: return SkillResult.Failure(name, "Missing 'query' argument")
        val conversationId = call.arguments["conversationId"]?.takeIf { it.isNotBlank() }
        val topK = call.arguments["topK"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        return withContext(Dispatchers.Default) {
            try {
                // Generic self-referential queries ("about me", "myself", "about Nick") carry no
                // topical content words, so a similarity search returns nothing and the model ends
                // up surfacing an empty result even when core memories exist. Short-circuit to a
                // snapshot of the user's saved facts instead. (#1012 RCA — memory test 1.)
                val userName = runCatching { userProfileRepository.getName() }.getOrNull()
                if (isGenericSelfQuery(query, userName)) {
                    val coreMemories = runCatching {
                        ragRepository.getAllUserCoreMemories()
                    }.getOrElse { e ->
                        Log.w(TAG, "getAllUserCoreMemories failed: ${e.message}", e)
                        emptyList()
                    }
                    val savedMemories = if (coreMemories.isNotEmpty()) {
                        coreMemories
                    } else {
                        val episodicFallback = runCatching {
                            ragRepository.searchCoreAndEpisodic(query, topK, includeSiblingContext = false)
                        }.getOrElse { e ->
                            Log.w(TAG, "generic self-query episodic fallback failed: ${e.message}", e)
                            emptyList()
                        }
                        filterSearchMemoryResults(query, episodicFallback, emptyList()).memoryResults
                    }
                    if (savedMemories.isEmpty()) {
                        return@withContext SkillResult.DirectReply("No relevant memories found.")
                    }
                    Log.d(TAG, "SearchMemorySkill: generic self-query '$query' → ${savedMemories.size} saved memories")
                    return@withContext SkillResult.DirectReply(buildSavedMemoryList(savedMemories))
                }

                val isPreferenceRecall = containsPersonalPreferenceCue(query)
                val messageResults = runCatching {
                    ragRepository.searchMessages(query, conversationId, topK)
                }.getOrElse { e ->
                    Log.w(TAG, "searchMessages failed: ${e.message}", e)
                    emptyList()
                }.filter { result ->
                    !isPreferenceRecall ||
                        (result.role == "user" && !isQuestionLikeMemoryMessage(result.content))
                }
                val memoryResults = runCatching {
                    ragRepository.searchCoreAndEpisodic(query, topK, includeSiblingContext = false)
                }.getOrElse { e ->
                    Log.w(TAG, "searchCoreAndEpisodic failed: ${e.message}", e)
                    emptyList()
                }
                val filtered = filterSearchMemoryResults(
                    query = query,
                    memoryResults = memoryResults,
                    messageResults = messageResults,
                )
                Log.d(
                    TAG,
                    "SearchMemorySkill: query='${query.take(60)}' conversationId=$conversationId " +
                        "→ ${memoryResults.size}/${filtered.memoryResults.size} memory results, " +
                        "${messageResults.size}/${filtered.messageResults.size} message results",
                )

                if (filtered.memoryResults.isEmpty() && filtered.messageResults.isEmpty()) {
                    // DirectReply: empty retrieval result is already the complete user-facing answer.
                    return@withContext SkillResult.DirectReply("No relevant memories found matching '$query'.")
                }

                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                val sb = StringBuilder("Relevant memory evidence for the user's question:\n\n")

                // Explicitly saved facts first — these are the most direct answer to "what do you remember".
                filtered.memoryResults.forEach { result ->
                    val sourceTag = if (result.source == "episodic") "Episodic Memory" else "Core Memory"
                    val dateStr = if (result.lastAccessedAt > 0L) fmt.format(Date(result.lastAccessedAt)) else "date unknown"
                    sb.appendLine("- [$sourceTag — $dateStr]")
                    sb.appendLine("  ${result.content.take(300)}")
                }

                // Message history results follow.
                if (filtered.messageResults.isNotEmpty()) {
                    filtered.messageResults.forEach { result ->
                        val role = if (result.role == "user") "You" else "Me"
                        val date = fmt.format(Date(result.timestamp))
                        sb.appendLine("- [Message — $date — conversation:${result.conversationId.take(8)}]")
                        sb.appendLine("  $role: ${result.content.take(300)}")
                    }
                }

                // Return retrieval evidence to the model so it can answer the user's question
                // naturally instead of exposing the raw search list.
                SkillResult.Success(sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "SearchMemorySkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to search memories")
            }
        }
    }
}

private fun buildSavedMemoryList(memories: List<MemorySearchResult>): String = buildString {
    appendLine("Here's what I remember about you:")
    appendLine()
    memories.forEach { memory -> appendLine("• ${memory.content.take(300)}") }
}.trimEnd()

/**
 * True when [query] is a generic self-referential request with no topical content words — e.g.
 * "about me", "myself", "everything about Nick" — for which a similarity search has nothing to
 * match against. Such queries should return the user's saved facts wholesale rather than an empty
 * result. Returns false as soon as any non-self token appears (e.g. "about my trip to Japan"),
 * preserving normal topical search for legitimate queries.
 */
internal fun isGenericSelfQuery(query: String, userName: String?): Boolean {
    val normalized = query.lowercase().trim()
    if (normalized.isEmpty()) return false
    val selfTokens = buildSet {
        addAll(
            listOf(
                "me", "myself", "i", "my", "mine",
                "about", "everything", "anything", "all", "know", "remember", "you", "your",
                "do", "does", "did", "what", "whats", "what's", "is", "tell", "recall",
            ),
        )
        userName?.lowercase()?.split(Regex("\\s+"))?.filterTo(this) { it.isNotBlank() }
    }
    val tokens = normalized.split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    // Must contain at least one explicit self pronoun / the user's name, not just filler words.
    val hasSelfReference = tokens.any { token ->
        token in setOf("me", "myself", "my", "mine") ||
            (userName != null && token.removeSuffix("'s") == userName.lowercase().substringBefore(' '))
    }
    if (!hasSelfReference) return false
    return tokens.all { it in selfTokens || it.removeSuffix("'s") in selfTokens }
}

internal fun containsPersonalPreferenceCue(query: String): Boolean =
    PERSONAL_PREFERENCE_CUE.containsMatchIn(query)

private fun isQuestionLikeMemoryMessage(content: String): Boolean =
    QUESTION_LIKE_MEMORY_MESSAGE.containsMatchIn(content.trimStart())

/** True when a user turn requests a saved personal fact and must be grounded in memory search. */
fun isPersonalMemoryRecallQuery(query: String, userName: String?): Boolean {
    val normalized = query.lowercase().trim()
    if (normalized.isEmpty() || isCurrentConversationRecallQueryNormalized(normalized)) return false
    if (isGenericSelfQuery(normalized, userName)) return true

    val hasRecallPhrase = PERSONAL_RECALL_PHRASES.any(normalized::contains)
    if (hasRecallPhrase) return hasPersonalRecallReference(normalized, userName)

    val startsAsQuestion = normalized.startsWith("what ") ||
        normalized.startsWith("what's ") ||
        normalized.startsWith("whats ") ||
        normalized.startsWith("which ")
    return startsAsQuestion &&
        hasPersonalRecallReference(normalized, userName) &&
        containsPersonalPreferenceCue(normalized)
}

/**
 * Identifies questions about the current chat rather than facts saved in memory.
 *
 * @return true when the recall phrase has no stored-topic words after it.
 */
fun isCurrentConversationRecallQuery(query: String): Boolean =
    isCurrentConversationRecallQueryNormalized(query.lowercase().trim())

private fun isCurrentConversationRecallQueryNormalized(query: String): Boolean {
    val recallPhrase = CURRENT_CONVERSATION_RECALL_PHRASE.find(query) ?: return false
    val wordsAfterPhrase = CURRENT_CONVERSATION_WORDS
        .findAll(query.substring(recallPhrase.range.last + 1))
        .map { it.value }
    return wordsAfterPhrase.none { it !in CURRENT_CONVERSATION_FILLER_WORDS }
}

private fun hasPersonalRecallReference(query: String, userName: String?): Boolean {
    val clauseEnd = query.indexOfFirst { it == '.' || it == '?' || it == '!' }
    val recallClause = if (clauseEnd < 0) query else query.substring(0, clauseEnd)
    if (PERSONAL_POSSESSIVE_REFERENCE.containsMatchIn(recallClause) ||
        PERSONAL_SELF_AS_SUBJECT.containsMatchIn(recallClause) ||
        ABOUT_SELF_REFERENCE.containsMatchIn(recallClause)
    ) {
        return true
    }
    val normalizedName = userName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    val escapedName = Regex.escape(normalizedName)
    return Regex("""\babout\s+$escapedName\b""").containsMatchIn(recallClause) ||
        Regex("""\b$escapedName(?:['’]s)\b""").containsMatchIn(recallClause)
}
