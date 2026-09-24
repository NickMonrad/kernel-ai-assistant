package com.kernel.ai.core.memory.vector

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.KiwiMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Rebuilds every semantic vector index from Room when model or distance semantics change. */
@Singleton
class EmbeddingIndexMigration @Inject constructor(
    private val embeddingEngine: EmbeddingEngine,
    private val vectorStore: VectorStore,
    private val messageDao: MessageDao,
    private val messageEmbeddingDao: MessageEmbeddingDao,
    private val coreMemoryDao: CoreMemoryDao,
    private val episodicMemoryDao: EpisodicMemoryDao,
    private val kiwiMemoryDao: KiwiMemoryDao,
    private val modelPreferences: ModelPreferences,
    @param:ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /** Returns false without touching old data until the new model can produce valid vectors. */
    suspend fun ensureCurrent(): Boolean = mutex.withLock {
        if (modelPreferences.getEmbeddingIndexIdentity() == INDEX_IDENTITY) {
            val hasUnvectorizedRows = try {
                coreMemoryDao.getUnvectorized().isNotEmpty() ||
                    episodicMemoryDao.getUnvectorized().isNotEmpty() ||
                    kiwiMemoryDao.getUnvectorized().isNotEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Could not inspect Room vectorization state; retaining existing indexes", e)
                return@withLock false
            }
            if (!hasUnvectorizedRows) return@withLock true
        }

        // A prior vector-table reset or a saved row without an available embedding can leave
        // current-identity Room rows unvectorized. Rebuild all indexes from canonical content.

        val probe = embeddingEngine.embedDocument("semantic index migration probe")
        if (probe.size != DIMENSIONS) {
            Log.i(TAG, "Arctic Embed unavailable; retaining Room data and deferring index migration")
            return@withLock false
        }

        try {
            TABLES.forEach(vectorStore::dropTable)
            TABLES.forEach { vectorStore.createTable(it, DIMENSIONS) }
            messageEmbeddingDao.deleteAll()

            for (message in messageDao.getAllForEmbedding()) {
                if (message.content.isBlank()) continue
                val vector = requireEmbedding(message.content)
                val rowId = messageEmbeddingDao.insert(
                    MessageEmbeddingEntity(messageId = message.id, conversationId = message.conversationId),
                )
                check(rowId > 0L) { "Could not recreate message embedding metadata for ${message.id}" }
                vectorStore.upsert(MESSAGE_TABLE, rowId, vector)
            }

            for (memory in coreMemoryDao.getAll()) {
                if (memory.content.isNotBlank()) {
                    vectorStore.upsert(CORE_TABLE, memory.rowId, requireEmbedding(memory.content))
                }
                coreMemoryDao.markVectorized(memory.rowId)
            }

            for (memory in episodicMemoryDao.getAll()) {
                if (memory.content.isNotBlank()) {
                    vectorStore.upsert(EPISODIC_TABLE, memory.rowId, requireEmbedding(memory.content))
                }
                episodicMemoryDao.markVectorized(memory.rowId)
            }

            for (memory in kiwiMemoryDao.getAll()) {
                if (memory.content.isNotBlank()) {
                    vectorStore.upsert(KIWI_TABLE, memory.rowId, requireEmbedding(memory.content))
                }
                kiwiMemoryDao.markVectorized(memory.rowId)
            }

            deleteLegacyEmbeddingGemmaAssets()
            modelPreferences.setEmbeddingIndexIdentity(INDEX_IDENTITY)
            Log.i(TAG, "Rebuilt all Arctic cosine indexes from Room canonical content")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Arctic vector index rebuild failed; retry will rebuild from Room", e)
            false
        }
    }

    private suspend fun requireEmbedding(content: String): FloatArray =
        embeddingEngine.embedDocument(content).also { vector ->
            check(vector.size == DIMENSIONS) { "Arctic Embed returned ${vector.size} dimensions; expected $DIMENSIONS" }
        }

    private suspend fun deleteLegacyEmbeddingGemmaAssets() {
        val modelDirectories = listOfNotNull(
            context.getExternalFilesDir("models"),
            File(context.filesDir, "models"),
        ).distinct()
        withContext(Dispatchers.IO) {
            LEGACY_EMBEDDING_GEMMA_FILES.forEach { fileName ->
                modelDirectories.forEach { directory ->
                    val file = File(directory, fileName)
                    check(!file.exists() || file.delete()) {
                        "Could not remove obsolete embedding asset ${file.name}"
                    }
                }
            }
        }
    }

    companion object {
        const val MESSAGE_TABLE = "message_embeddings"
        const val CORE_TABLE = "core_memories_vec"
        const val EPISODIC_TABLE = "episodic_memories_vec"
        const val KIWI_TABLE = "kiwi_memories_vec"
        const val DIMENSIONS = 768
        const val INDEX_IDENTITY =
            "arctic-embed-m-v1.5:768:" +
                "17c2211fbd759e769b3030837d8259a86a2f7603a0f64222fde14474e4c3054d:" +
                "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3:cosine-v1"
        private val LEGACY_EMBEDDING_GEMMA_FILES = listOf(
            "embeddinggemma-300M_seq512_mixed-precision.tflite",
            "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite",
            "sentencepiece.model",
        )
        private const val TAG = "EmbeddingIndexMigration"
        private val TABLES = listOf(MESSAGE_TABLE, CORE_TABLE, EPISODIC_TABLE, KIWI_TABLE)
    }
}
