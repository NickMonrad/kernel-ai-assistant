package com.kernel.ai.core.memory.usecase

import android.util.Log
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.Gemma4InitLock
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class NoteSmartTitleUseCase @Inject constructor(
    private val noteDao: NoteDao,
    private val inferenceEngine: InferenceEngine,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val toolProvider: ToolProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun schedule(noteId: Long) {
        jobs.remove(noteId)?.cancel()
        lateinit var job: Job
        job = scope.launch {
            try {
                delay(DEBOUNCE_MS)
                val note = noteDao.getNoteById(noteId) ?: return@launch
                if (note.smartTitleGenerated || !note.title.isNullOrBlank()) return@launch
                if (note.content.isBlank()) return@launch

                val engineReady = awaitInferenceEngineReady(
                    isReady = inferenceEngine.isReady,
                    timeoutMs = ENGINE_READY_TIMEOUT_MS,
                ) {
                    Gemma4InitLock.mutex.withLock {
                        if (inferenceEngine.isReady.value) return@withLock

                        val model = modelDownloadManager.preferredConversationModel()
                        val modelPath = modelDownloadManager.getModelPath(model) ?: return@withLock
                        val settings = modelSettingsRepository.getSettings(model.modelId)
                        inferenceEngine.initialize(
                            ModelConfig(
                                modelPath = modelPath,
                                maxTokens = settings.contextWindowSize,
                                temperature = settings.temperature,
                                topP = settings.topP,
                                topK = settings.topK,
                                thinkingEnabled = settings.showThinkingProcess,
                                speculativeDecodingEnabled = settings.speculativeDecodingEnabled,
                                toolProvider = toolProvider,
                            ),
                        )
                    }
                }
                if (!engineReady) {
                    Log.w(TAG, "Smart title generation skipped; inference engine did not become ready for note $noteId")
                    return@launch
                }
                val currentNote = noteDao.getNoteById(noteId) ?: return@launch
                if (currentNote.smartTitleGenerated || !currentNote.title.isNullOrBlank()) return@launch
                val content = currentNote.content.trim()
                if (content.isBlank()) return@launch
                val generated = generateTitle(content) ?: return@launch
                val updated = noteDao.updateNoteTitleConditionally(noteId, generated)

                if (updated == 0) {
                    Log.d(TAG, "Skipped smart title update for note $noteId; note changed while generating")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Smart title generation failed for note $noteId: ${e.message}", e)
            } finally {
                jobs.remove(noteId, job)
            }
        }
        jobs[noteId] = job
    }

    private suspend fun generateTitle(content: String): String? {
        val excerpt = content.take(EXCERPT_LIMIT).trim()
        if (excerpt.isBlank()) return null

        val raw = inferenceEngine.generateOnce(
            prompt = "Generate a title for this note.\n\nNote content:\n$excerpt",
            systemPrompt = SYSTEM_PROMPT,
            thinkingEnabled = false,
        )
        return sanitize(raw)
    }

    private fun sanitize(raw: String): String? {
        val firstLine = raw
            .trim()
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        if (firstLine.isBlank()) return null

        val cleaned = firstLine
            .replace(PREAMBLE_RE, "")
            .replace(MARKDOWN_RE, "")
            .trim('"', '\'', '“', '”')
            .trimEnd('.', '!', '?', '。', '！', '？', ':', ';')
            .replace(WHITESPACE_RE, " ")
            .trim()
        if (cleaned.isBlank()) return null

        return capWords(cleaned, MAX_WORDS, MAX_CHARS).ifBlank { null }
    }

    private fun capWords(input: String, maxWords: Int, maxChars: Int): String {
        val words = input.split(' ')
        val builder = StringBuilder()
        var count = 0
        for (word in words) {
            if (word.isBlank()) continue
            val nextLength = if (builder.isEmpty()) word.length else builder.length + 1 + word.length
            if (count >= maxWords) break
            if (nextLength > maxChars) {
                if (builder.isEmpty()) {
                    builder.append(word.take(maxChars))
                }
                break
            }
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(word)
            count++
        }
        return builder.toString().trim()
    }

    companion object {
        private const val TAG = "KernelAI"
        private const val DEBOUNCE_MS = 500L
        private const val ENGINE_READY_TIMEOUT_MS = 30_000L
        private const val EXCERPT_LIMIT = 400
        private const val MAX_WORDS = 5
        private const val MAX_CHARS = 60

        private val SYSTEM_PROMPT = """
            You are a title generator for personal notes.
            Output ONLY one plain-text title.
            Rules:
            - 3 to 5 words
            - Maximum 60 characters
            - Capture the note's main subject
            - Prefer wording already present in the note
            - If the note is very short, stay literal and generic rather than inventing details
            - No quotes, markdown, labels, emojis, explanations, or trailing punctuation
            - One line only
        """.trimIndent()
        private val PREAMBLE_RE = Regex("^(?:Title|Note(?: title)?|Suggested title|Here(?:'s| is) a title)[:\\s-]*", RegexOption.IGNORE_CASE)
        private val MARKDOWN_RE = Regex("[`*_]+")
        private val WHITESPACE_RE = Regex("\\s+")
    }
}

internal suspend fun awaitInferenceEngineReady(
    isReady: StateFlow<Boolean>,
    timeoutMs: Long,
    initializeIfNeeded: suspend () -> Unit,
): Boolean = withTimeoutOrNull(timeoutMs) {
    initializeIfNeeded()
    isReady.first { it }
    true
} == true
