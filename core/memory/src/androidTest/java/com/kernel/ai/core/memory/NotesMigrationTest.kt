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
    fun `migration 48 to 51 creates notes table with correct schema`() {
        helper.createDatabase(testDbName, 48).close()
        helper.runMigrationsAndValidate(
            testDbName,
            51,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
            KernelDatabase.MIGRATION_50_51,
        ).close()

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                assertEquals(0, noteDao.getActiveNoteCount())
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 51 adds all expected columns`() {
        helper.createDatabase(testDbName, 48).close()
        val db = helper.runMigrationsAndValidate(
            testDbName,
            51,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
            KernelDatabase.MIGRATION_50_51,
        )

        // Query the notes table directly using the migrated database
        db.query("PRAGMA table_info('notes')").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("Expected id column", "id" in columns)
            assertTrue("Expected title column", "title" in columns)
            assertTrue("Expected content column", "content" in columns)
            assertTrue("Expected created_at column", "created_at" in columns)
            assertTrue("Expected updated_at column", "updated_at" in columns)
            assertTrue("Expected pinned column", "pinned" in columns)
            assertTrue("Expected archived_at column", "archived_at" in columns)
            assertTrue("Expected display_order column", "display_order" in columns)
            assertTrue("Expected smart_title_generated column", "smart_title_generated" in columns)
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 51 creates required indices`() {
        helper.createDatabase(testDbName, 48).close()
        val db = helper.runMigrationsAndValidate(
            testDbName,
            51,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
            KernelDatabase.MIGRATION_50_51,
        )

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                noteDao.insertNote(NoteEntity(title = "A", content = "a", archivedAt = 1000))
                noteDao.insertNote(NoteEntity(title = "B", content = "b"))
                noteDao.insertNote(NoteEntity(title = "C", content = "c"))
                val active = noteDao.getAllActiveNotes()
                assertEquals(2, active.size)
                assertEquals(2, noteDao.getActiveNoteCount())
            }
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 48 to 51 allows nullable title`() {
        helper.createDatabase(testDbName, 48).close()
        val db = helper.runMigrationsAndValidate(
            testDbName,
            51,
            true,
            KernelDatabase.MIGRATION_48_49,
            KernelDatabase.MIGRATION_49_50,
            KernelDatabase.MIGRATION_50_51,
        )

        openMigratedDatabase().useDb { noteDao ->
            runBlocking {
                noteDao.insertNote(NoteEntity(content = "no title"))
                assertEquals(1, noteDao.getActiveNoteCount())
            }
        }
        db.close()
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
