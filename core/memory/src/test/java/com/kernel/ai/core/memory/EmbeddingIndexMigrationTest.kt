package com.kernel.ai.core.memory

import android.content.Context
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.KiwiMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.KiwiMemoryEntity
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.vector.EmbeddingIndexMigration
import com.kernel.ai.core.memory.vector.VectorStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class EmbeddingIndexMigrationTest {
    private val embeddingEngine: EmbeddingEngine = mockk()
    private val vectorStore: VectorStore = mockk()
    private val messageDao: MessageDao = mockk()
    private val messageEmbeddingDao: MessageEmbeddingDao = mockk()
    private val coreMemoryDao: CoreMemoryDao = mockk()
    private val episodicMemoryDao: EpisodicMemoryDao = mockk()
    private val kiwiMemoryDao: KiwiMemoryDao = mockk()
    private val modelPreferences: ModelPreferences = mockk()

    private val context: Context = mockk()
    private lateinit var temporaryRoot: File
    private lateinit var externalModelsDirectory: File
    private lateinit var internalFilesDirectory: File
    private lateinit var migration: EmbeddingIndexMigration

    @BeforeEach
    fun setUp() {
        temporaryRoot = File(System.getProperty("java.io.tmpdir"), "arctic-migration-${System.nanoTime()}")
        externalModelsDirectory = File(temporaryRoot, "external/models").apply { mkdirs() }
        internalFilesDirectory = File(temporaryRoot, "internal").apply { mkdirs() }
        every { context.getExternalFilesDir("models") } returns externalModelsDirectory
        every { context.filesDir } returns internalFilesDirectory
        migration = EmbeddingIndexMigration(
            embeddingEngine = embeddingEngine,
            vectorStore = vectorStore,
            messageDao = messageDao,
            messageEmbeddingDao = messageEmbeddingDao,
            coreMemoryDao = coreMemoryDao,
            episodicMemoryDao = episodicMemoryDao,
            kiwiMemoryDao = kiwiMemoryDao,
            modelPreferences = modelPreferences,
            context = context,
        )
        coEvery { modelPreferences.getEmbeddingIndexIdentity() } returns null
        coEvery { modelPreferences.setEmbeddingIndexIdentity(any()) } just Runs
        coEvery { coreMemoryDao.getUnvectorized() } returns emptyList()
        coEvery { episodicMemoryDao.getUnvectorized() } returns emptyList()
        coEvery { kiwiMemoryDao.getUnvectorized() } returns emptyList()
        coEvery { embeddingEngine.embedDocument(any()) } returns FloatArray(EmbeddingIndexMigration.DIMENSIONS) { 0.25f }
        coEvery { messageEmbeddingDao.deleteAll() } just Runs
        coEvery { messageEmbeddingDao.insert(any()) } returns 1L
        coEvery { messageDao.getAllForEmbedding() } returns listOf(
            MessageEntity("message-1", "conversation-1", "user", "canonical message", null, 1L),
            MessageEntity("message-blank", "conversation-1", "user", "   ", null, 2L),
        )
        coEvery { coreMemoryDao.getAll() } returns listOf(
            CoreMemoryEntity(rowId = 11L, id = "core-1", content = "canonical core", createdAt = 1L, lastAccessedAt = 1L, source = "user"),
        )
        coEvery { coreMemoryDao.markVectorized(any()) } just Runs
        coEvery { episodicMemoryDao.getAll() } returns listOf(
            EpisodicMemoryEntity(rowId = 12L, id = "episode-1", conversationId = "conversation-1", content = "canonical episode", createdAt = 1L),
        )
        coEvery { episodicMemoryDao.markVectorized(any()) } just Runs
        coEvery { kiwiMemoryDao.getAll() } returns listOf(
            KiwiMemoryEntity(rowId = 13L, id = "kiwi-1", content = "canonical kiwi", createdAt = 1L, lastAccessedAt = 1L, source = "persona"),
        )
        coEvery { kiwiMemoryDao.markVectorized(any()) } just Runs
        every { vectorStore.dropTable(any()) } just Runs
        every { vectorStore.createTable(any(), any()) } just Runs
        every { vectorStore.upsert(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun `removes legacy model and tokenizer only after complete index rebuild`() = runTest {
        val legacyAssets = listOf(
            File(externalModelsDirectory, "embeddinggemma-300M_seq512_mixed-precision.tflite"),
            File(externalModelsDirectory, "embeddinggemma-300M_seq512_mixed-precision.qualcomm.sm8550.tflite"),
            File(File(internalFilesDirectory, "models"), "sentencepiece.model"),
        )
        legacyAssets.forEach { asset ->
            asset.parentFile?.mkdirs()
            asset.writeText("legacy")
        }
        coEvery { modelPreferences.setEmbeddingIndexIdentity(any()) } answers {
            assertFalse(legacyAssets.any { it.exists() })
        }

        assertTrue(migration.ensureCurrent())

        assertFalse(legacyAssets.any { it.exists() })
        coVerify(exactly = 1) { modelPreferences.setEmbeddingIndexIdentity(EmbeddingIndexMigration.INDEX_IDENTITY) }
    }

    @Test
    fun `rebuilds all four indexes from canonical Room content before recording identity`() = runTest {
        coEvery { modelPreferences.setEmbeddingIndexIdentity(any()) } coAnswers {
            verify(exactly = 4) { vectorStore.createTable(any(), EmbeddingIndexMigration.DIMENSIONS) }
            verify(exactly = 4) { vectorStore.upsert(any(), any(), any()) }
            coVerify(exactly = 1) { coreMemoryDao.markVectorized(11L) }
            coVerify(exactly = 1) { episodicMemoryDao.markVectorized(12L) }
            coVerify(exactly = 1) { kiwiMemoryDao.markVectorized(13L) }
        }
        assertTrue(migration.ensureCurrent())

        verify(exactly = 4) { vectorStore.dropTable(any()) }
        verify(exactly = 4) { vectorStore.createTable(any(), EmbeddingIndexMigration.DIMENSIONS) }
        coVerify(exactly = 1) { messageEmbeddingDao.deleteAll() }
        coVerify(exactly = 1) { messageEmbeddingDao.insert(MessageEmbeddingEntity(messageId = "message-1", conversationId = "conversation-1")) }
        coVerify(exactly = 0) { messageEmbeddingDao.insert(match { it.messageId == "message-blank" }) }
        verify(exactly = 1) { vectorStore.upsert(EmbeddingIndexMigration.MESSAGE_TABLE, 1L, any()) }
        verify(exactly = 1) { vectorStore.upsert(EmbeddingIndexMigration.CORE_TABLE, 11L, any()) }
        verify(exactly = 1) { vectorStore.upsert(EmbeddingIndexMigration.EPISODIC_TABLE, 12L, any()) }
        verify(exactly = 1) { vectorStore.upsert(EmbeddingIndexMigration.KIWI_TABLE, 13L, any()) }
        coVerify(exactly = 1) { coreMemoryDao.markVectorized(11L) }
        coVerify(exactly = 1) { episodicMemoryDao.markVectorized(12L) }
        coVerify(exactly = 1) { kiwiMemoryDao.markVectorized(13L) }
        coVerify(exactly = 1) { modelPreferences.setEmbeddingIndexIdentity(EmbeddingIndexMigration.INDEX_IDENTITY) }
    }

    @Test
    fun `current identity skips rebuilding when all Room rows are vectorized`() = runTest {
        coEvery { modelPreferences.getEmbeddingIndexIdentity() } returns EmbeddingIndexMigration.INDEX_IDENTITY

        assertTrue(migration.ensureCurrent())

        coVerify(exactly = 0) { embeddingEngine.embedDocument(any()) }
        verify(exactly = 0) { vectorStore.dropTable(any()) }
    }

    @Test
    fun `current identity rebuilds when a Room row is unvectorized`() = runTest {
        coEvery { modelPreferences.getEmbeddingIndexIdentity() } returns EmbeddingIndexMigration.INDEX_IDENTITY
        coEvery { kiwiMemoryDao.getUnvectorized() } returns listOf(
            KiwiMemoryEntity(rowId = 13L, id = "kiwi-1", content = "canonical kiwi", createdAt = 1L, lastAccessedAt = 1L, source = "persona"),
        )

        assertTrue(migration.ensureCurrent())

        verify(exactly = 4) { vectorStore.dropTable(any()) }
        verify(exactly = 4) { vectorStore.createTable(any(), EmbeddingIndexMigration.DIMENSIONS) }
        coVerify(exactly = 1) { modelPreferences.setEmbeddingIndexIdentity(EmbeddingIndexMigration.INDEX_IDENTITY) }
    }

    @Test
    fun `current unvectorized index waits for Arctic without touching existing tables`() = runTest {
        coEvery { modelPreferences.getEmbeddingIndexIdentity() } returns EmbeddingIndexMigration.INDEX_IDENTITY
        coEvery { kiwiMemoryDao.getUnvectorized() } returns listOf(
            KiwiMemoryEntity(rowId = 13L, id = "kiwi-1", content = "canonical kiwi", createdAt = 1L, lastAccessedAt = 1L, source = "persona"),
        )
        coEvery { embeddingEngine.embedDocument("semantic index migration probe") } returns FloatArray(0)

        assertFalse(migration.ensureCurrent())

        verify(exactly = 0) { vectorStore.dropTable(any()) }
        verify(exactly = 0) { vectorStore.createTable(any(), any()) }
        coVerify(exactly = 0) { messageEmbeddingDao.deleteAll() }
        coVerify(exactly = 0) { modelPreferences.setEmbeddingIndexIdentity(any()) }
    }

    @Test
    fun `does not destroy old indexes when Arctic cannot provide a probe vector`() = runTest {
        coEvery { embeddingEngine.embedDocument("semantic index migration probe") } returns FloatArray(0)

        assertFalse(migration.ensureCurrent())

        verify(exactly = 0) { vectorStore.dropTable(any()) }
        verify(exactly = 0) { vectorStore.createTable(any(), any()) }
        coVerify(exactly = 0) { messageEmbeddingDao.deleteAll() }
        coVerify(exactly = 0) { modelPreferences.setEmbeddingIndexIdentity(any()) }
    }

    @Test
    fun `failed partial rebuild retains legacy assets until retry succeeds`() = runTest {
        val legacyAsset = File(
            externalModelsDirectory,
            "embeddinggemma-300M_seq512_mixed-precision.tflite",
        ).apply { writeText("legacy") }
        every { vectorStore.createTable(any(), any()) } throws IllegalStateException("simulated table creation failure")

        assertFalse(migration.ensureCurrent())
        assertTrue(legacyAsset.exists())
        coVerify(exactly = 0) { modelPreferences.setEmbeddingIndexIdentity(any()) }

        every { vectorStore.createTable(any(), any()) } just Runs
        assertTrue(migration.ensureCurrent())

        assertFalse(legacyAsset.exists())
        verify(exactly = 8) { vectorStore.dropTable(any()) }
        coVerify(exactly = 1) { modelPreferences.setEmbeddingIndexIdentity(EmbeddingIndexMigration.INDEX_IDENTITY) }
    }
}
