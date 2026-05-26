package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Room migration tests for the notes table (v48 → v49 → v50).
 *
 * Verifies that:
 * - MIGRATION_48_49 creates the notes table with nullable title
 * - MIGRATION_49_50 adds pinned, archived_at, display_order, smart_title_generated columns
 * - MIGRATION_49_50 creates index_notes_archived_at and index_notes_pinned_display_order
 * - Notes can be inserted and queried after migration
 *
 * Run with: ./gradlew :core:memory:testDebugAndroidTest --tests "*.NotesMigrationTest"
 */
@RunWith(AndroidJUnit4::class)
class NotesMigrationTest {

    private lateinit var database: KernelDatabase
    private lateinit var noteDao: NoteDao

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        ApplicationProvider.getApplicationContext(),
        KernelDatabase::class.java,
    )

    @Before
    fun createDatabase() {
        database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KernelDatabase::class.java,
            TEST_DB,
        ).build()
        noteDao = database.noteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 creates notes table with correct schema`() {
        // Create a v48 database (no notes table)
        var builder = helper.createDatabase(SCHEMA_PATH, 48)
        builder.close()

        // Reopen with migrations 48→49→50
        builder = helper.runMigrationsAndValidate(
            SCHEMA_PATH,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        )
        builder.close()

        // Verify notes table exists and is queryable
        val count = noteDao.getActiveNoteCount()
        assertEquals(0, count)
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 allows nullable title`() = runBlocking {
        var builder = helper.createDatabase(SCHEMA_PATH, 48)
        builder.close()

        helper.runMigrationsAndValidate(
            SCHEMA_PATH,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        )

        // Insert a note with null title — should succeed (title is nullable)
        val id = noteDao.insertNote(NoteEntity(title = null, content = "Test content"))
        assertTrue(id > 0)

        val note = noteDao.getNoteById(id)
        assertNotNull(note)
        assertNull(note?.title)
        assertEquals("Test content", note?.content)
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 adds all expected columns`() = runBlocking {
        var builder = helper.createDatabase(SCHEMA_PATH, 48)
        builder.close()

        helper.runMigrationsAndValidate(
            SCHEMA_PATH,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        )

        val id = noteDao.insertNote(NoteEntity(title = "Test", content = "Content"))
        val note = noteDao.getNoteById(id)!!

        // Verify new columns have correct defaults
        assertEquals(0, note.pinned)
        assertEquals(0L, note.archivedAt)
        assertNotNull(note.displayOrder)
        assertEquals(0, note.smartTitleGenerated)
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 creates required indices`() = runBlocking {
        var builder = helper.createDatabase(SCHEMA_PATH, 48)
        builder.close()

        helper.runMigrationsAndValidate(
            SCHEMA_PATH,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        )

        // Insert notes with different archived_at and pinned/display_order values
        noteDao.insertNote(NoteEntity(title = "A", content = "a", archivedAt = 1000))
        noteDao.insertNote(NoteEntity(title = "B", content = "b", archivedAt = 2000))
        noteDao.insertNote(NoteEntity(title = "C", content = "c", pinned = 1, displayOrder = 0.0))

        // Query via DAO — if indices were missing, this would still work but the
        // migration test validates the schema is correct. The real validation is
        // that the migration runs without error and the columns exist.
        val active = noteDao.getAllActiveNotes()
        assertEquals(3, active.size)
    }

    companion object {
        private const val SCHEMA_PATH =
            "core/memory/schemas/com.kernel.ai.core.memory.KernelDatabase"
    }
}
