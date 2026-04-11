package com.kernel.ai.core.memory.usecase

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Distils a conversation into standalone episodic memory sentences on conversation close.
 *
 * Fetches messages since [lastDistilledAt] (or all messages if null), formats them as a
 * transcript, calls the inference engine for a summary, embeds each sentence and stores
 * them as episodic memories. Updates [ConversationDao.updateLastDistilledAt] on success.
 */
@Singleton
class EpisodicDistillationUseCase @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val memoryRepository: MemoryRepository,
    private val inferenceEngine: InferenceEngine,
    private val embeddingEngine: EmbeddingEngine,
) {

    companion object {
        private const val TAG = "KernelAI"
        private const val MIN_TURNS = 4
        private val LEADING_JUNK = Regex("""^[\s\d]+[.)]\s*|^[-•*]\s*""")
    }

    /**
     * Distil the conversation identified by [conversationId].
     *
     * @param conversationId The conversation to distil.
     * @param lastDistilledAt Timestamp of the last distillation run, or null to distil everything.
     */
    suspend fun distil(conversationId: String, lastDistilledAt: Long?) {
        try {
            val messages = if (lastDistilledAt != null) {
                messageDao.getByConversationSince(conversationId, lastDistilledAt)
            } else {
                messageDao.getByConversation(conversationId)
            }

            if (messages.size < MIN_TURNS) {
                Log.d(TAG, "Episodic distillation skipped for $conversationId: only ${messages.size} messages (min $MIN_TURNS)")
                return
            }

            val transcript = messages.joinToString("\n") { msg ->
                val label = if (msg.role == "user") "User" else "Assistant"
                "$label: ${msg.content}"
            }

            val prompt = """
Summarise the key facts, preferences, and events from this conversation in 3–5 short sentences. Each sentence should be a standalone fact. Output only the sentences, one per line, with no numbering or bullet points.

[CONVERSATION]
$transcript
[END CONVERSATION]
            """.trimIndent()

            val response = inferenceEngine.generateOnce(prompt)
            if (response.isBlank()) {
                Log.w(TAG, "Episodic distillation returned blank response for $conversationId")
                return
            }

            val sentences = response.lines()
                .map { LEADING_JUNK.replace(it.trim(), "") }
                .filter { it.isNotBlank() }
            if (sentences.isEmpty()) {
                Log.w(TAG, "Episodic distillation: no sentences parsed for $conversationId")
                return
            }

            sentences.forEach { sentence ->
                val vector = embeddingEngine.embed(sentence)
                memoryRepository.addEpisodicMemory(conversationId, sentence, vector)
            }

            conversationDao.updateLastDistilledAt(conversationId, System.currentTimeMillis())
            Log.i(TAG, "Episodic distillation complete for $conversationId: ${sentences.size} memories stored")
        } catch (e: Exception) {
            Log.w(TAG, "Episodic distillation failed for $conversationId: ${e.message}")
        }
    }
}
