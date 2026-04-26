package com.kernel.ai.core.memory.usecase

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.repository.MemoryRepository
import org.json.JSONObject
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
        /** Minimum character length for a distilled sentence to be worth embedding and storing. */
        private const val MIN_SENTENCE_LENGTH = 20
        private const val MIN_GROUNDED_TOKEN_OVERLAP = 2
        private val LEADING_JUNK = Regex("""^[\s\d]+[.)]\s*|^[-•*]\s*""")

        /**
         * Skills whose results are ephemeral device/system state — distilled sentences about
         * these pollute future RAG with stale facts like "The torch is on" (#614).
         */
        private val EPHEMERAL_SKILLS = setOf(
            "run_intent", "toggle_flashlight_on", "toggle_flashlight_off",
            "get_weather", "get_system_info", "get_time", "get_date",
            "set_alarm", "set_timer", "set_volume", "set_brightness",
            "toggle_dnd", "toggle_wifi", "toggle_bluetooth",
        )

        /**
         * Sentence-level post-filter: returns true if the sentence describes transient device
         * state that would be stale and misleading when retrieved in future conversations.
         */
        private fun isEphemeralSentence(sentence: String): Boolean {
            val lower = sentence.lowercase()
            return EPHEMERAL_PATTERNS.any { lower.contains(it) }
        }

        private val EPHEMERAL_PATTERNS = listOf(
            "torch", "flashlight", "turned on the light", "turned off the light",
            "lights on", "lights off", "light on", "light off", "illuminat",
            "smart home", "smart_home", "couldn't turn", "could not turn",
            "unable to turn", "set an alarm", "set a timer", "alarm set",
            "timer set", "volume set", "brightness set", "wi-fi", "wifi",
            "bluetooth", "do not disturb", "dnd",
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "with", "this", "from", "have", "has",
            "had", "was", "were", "are", "you", "your", "about", "into", "they",
            "them", "their", "just", "than", "then", "only", "been", "being",
            "while", "when", "where", "what", "which", "after", "before", "user",
            "assistant", "jandal", "nick",
        )
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

            // Strip device-action assistant turns from the transcript — they carry ephemeral
            // device state ("Torch enabled", "smart_home_off failed") that would be misleading
            // if surfaced as a memory in a future conversation (#614).
            val filteredMessages = messages.filter { msg ->
                if (msg.role != "assistant" || msg.toolCallJson == null) return@filter true
                val skillName = runCatching {
                    JSONObject(msg.toolCallJson).getString("skillName")
                }.getOrNull() ?: return@filter true
                skillName !in EPHEMERAL_SKILLS
            }

            if (filteredMessages.size < MIN_TURNS) {
                Log.d(TAG, "Episodic distillation skipped for $conversationId: only ${filteredMessages.size} non-ephemeral messages after filtering (min $MIN_TURNS)")
                return
            }

            val transcript = filteredMessages.joinToString("\n") { msg ->
                val label = if (msg.role == "user") "User" else "Assistant"
                "$label: ${msg.content}"
            }

            val sentenceRange = when {
                filteredMessages.size >= 17 -> "6–10 sentences"
                filteredMessages.size >= 9  -> "5–8 sentences"
                else                        -> "3–5 sentences"
            }

            val prompt = """
Summarise the key facts, preferences, and events from this conversation in $sentenceRange. Each sentence should be a standalone fact about the user's interests, preferences, or knowledge. Do not include device control commands, smart home actions, alarms, timers, or other transient device state. Output only the sentences, one per line, with no numbering or bullet points.

[CONVERSATION]
$transcript
[END CONVERSATION]
            """.trimIndent()

            // Distillation runs when the chat VM is closing. Reset the system prompt first so the
            // summariser only sees the transcript below rather than the active chat session's
            // user profile or prior KV-cache context.
            inferenceEngine.updateSystemPrompt(
                """
You are distilling a conversation transcript into episodic memories.
Use ONLY facts explicitly present in the transcript provided by the user.
Do NOT use any hidden system prompt, stored user profile, or prior conversation context.
Return only standalone memory sentences, one per line, with no bullets or numbering.
                """.trimIndent()
            )
            val response = inferenceEngine.generateOnce(prompt)
            if (response.isBlank()) {
                Log.w(TAG, "Episodic distillation returned blank response for $conversationId")
                return
            }

            val transcriptTokens = tokenize(transcript)
            val sentences = response.lines()
                .map { LEADING_JUNK.replace(it.trim(), "") }
                .filter { it.length >= MIN_SENTENCE_LENGTH }
                .filterNot { isEphemeralSentence(it) }
                .filter { sentence ->
                    val grounded = isGroundedInTranscript(sentence, transcriptTokens)
                    if (!grounded) {
                        Log.w(TAG, "Episodic distillation dropped ungrounded sentence: $sentence")
                    }
                    grounded
                }

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

    private fun tokenize(text: String): Set<String> =
        Regex("[A-Za-z][A-Za-z0-9'-]{2,}")
            .findAll(text.lowercase())
            .map { it.value.trim('\'', '-') }
            .filter { it !in STOP_WORDS }
            .toSet()

    private fun isGroundedInTranscript(sentence: String, transcriptTokens: Set<String>): Boolean {
        if (transcriptTokens.isEmpty()) return false
        val sentenceTokens = tokenize(sentence)
        if (sentenceTokens.isEmpty()) return false
        val overlap = sentenceTokens.count { it in transcriptTokens }
        val requiredOverlap = maxOf(
            MIN_GROUNDED_TOKEN_OVERLAP,
            kotlin.math.ceil(sentenceTokens.size * 0.3).toInt(),
        )
        return overlap >= requiredOverlap
    }
}
