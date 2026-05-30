package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration tests for the notes table (v48 → v49 → v50).
 *
 * Verifies that:
 * - MIGRATION_48_49 creates the notes table with nullable title
 * - MIGRATION_49_50 adds pinned, archived_at, display_order, smart_title_generated columns
 * - MIGRATION_49_50 creates index_notes_archived_at and index_notes_pinned_display_order
 * - Notes can be inserted and queried after migration
 */
@RunWith(AndroidJUnit4::class)
class NotesMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 creates notes table with correct schema`() {
        helper.createDatabase(testDbName, 48).close()
        helper.runMigrationsAndValidate(
            testDbName,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        ).close()

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                assertEquals(0, noteDao.getActiveNoteCount())
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 allows nullable title`() {
        helper.createDatabase(testDbName, 48).close()
        helper.runMigrationsAndValidate(
            testDbName,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        ).close()

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                val id = noteDao.insertNote(NoteEntity(title = null, content = "Test content"))
                assertTrue(id > 0)

                val note = noteDao.getNoteById(id)
                assertNotNull(note)
                assertNull(note?.title)
                assertEquals("Test content", note?.content)
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 adds all expected columns`() {
        helper.createDatabase(testDbName, 48).close()
        helper.runMigrationsAndValidate(
            testDbName,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        ).close()

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                val id = noteDao.insertNote(NoteEntity(title = "Test", content = "Content"))
                val note = noteDao.getNoteById(id)!!

                assertEquals(false, note.pinned)
                assertEquals(0L, note.archivedAt)
                assertTrue(note.displayOrder >= 0.0)
                assertEquals(false, note.smartTitleGenerated)
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 50 creates required indices`() {
        helper.createDatabase(testDbName, 48).close()
        helper.runMigrationsAndValidate(
            testDbName,
            50,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
        ).close()

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                noteDao.insertNote(NoteEntity(title = "A", content = "a", archivedAt = 1000))
                noteDao.insertNote(NoteEntity(title = "B", content = "b", archivedAt = 2000))
                noteDao.insertNote(NoteEntity(title = "C", content = "c", pinned = true, displayOrder = 0.0))

                val active = noteDao.getAllActiveNotes()
                assertEquals(3, active.size)
            }
        }
    }

    private fun openMigratedDatabase(): KernelDatabase {
        return Room.databaseBuilder(context, KernelDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()
    }

    private inline fun KernelDatabase.useDb(block: (NoteDao) -> Unit) {
        try {
            block(noteDao())
        } finally {
            close()
        }
    }
}
