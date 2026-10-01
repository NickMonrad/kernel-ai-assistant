package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListsMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "lists-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    @Test
    @Throws(IOException::class)
    fun `migration 51 to 52 assigns stable identities and preserves list data`() {
        val oldDb = helper.createDatabase(testDbName, 51)
        oldDb.execSQL(
            "INSERT INTO lists (name, createdAt, updatedAt, pinned, displayOrder, archivedAt) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("Groceries", 1L, 2L, 0, 0, null),
        )
        oldDb.execSQL(
            "INSERT INTO list_items (listId, text, createdAt, updatedAt, checked, dueAt, isFavourite, notificationTime, displayOrder) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(1L, "Milk", 3L, 4L, 0, null, 0, null, 7L),
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(testDbName, 52, true, KernelDatabase.MIGRATION_51_52)
        db.query("SELECT collectionId, canonicalTitle, lifecycle FROM lists WHERE id = 1").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.getString(cursor.getColumnIndexOrThrow("collectionId")).isBlank())
            assertEquals("Groceries", cursor.getString(cursor.getColumnIndexOrThrow("canonicalTitle")))
            assertEquals("ACTIVE", cursor.getString(cursor.getColumnIndexOrThrow("lifecycle")))
        }
        db.query("SELECT itemId, collectionId, orderKey FROM list_items WHERE id = 1").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.getString(cursor.getColumnIndexOrThrow("itemId")).isBlank())
            assertNotEquals("", cursor.getString(cursor.getColumnIndexOrThrow("collectionId")))
            assertEquals("7", cursor.getString(cursor.getColumnIndexOrThrow("orderKey")))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 54 to 55 adds empty descriptions without losing item content`() {
        val oldDb = helper.createDatabase(testDbName, 54)
        oldDb.execSQL(
            "INSERT INTO list_items (listId, text, createdAt, updatedAt, checked, dueAt, isFavourite, notificationTime, displayOrder, itemId, collectionId, orderKey, textLogicalClock, textStampActorId, checkedLogicalClock, checkedStampActorId, dueAtLogicalClock, dueAtStampActorId, placementLogicalClock, placementStampActorId, lifecycle, lifecycleLogicalClock, lifecycleStampActorId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(1L, "Keep me", 3L, 4L, 0, null, 0, null, 0L, "item-1", "collection-1", "0", 1L, "test", 1L, "test", 1L, "test", 1L, "test", "ACTIVE", 1L, "test"),
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(testDbName, 55, true, KernelDatabase.MIGRATION_54_55)
        db.query("SELECT text, description, descriptionLogicalClock, descriptionStampActorId FROM list_items WHERE itemId = 'item-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Keep me", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("description")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("descriptionLogicalClock")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("descriptionStampActorId")))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 56 to 57 defaults access to available and unsynced work to resolved`() {
        val oldDb = helper.createDatabase(testDbName, 56)
        oldDb.execSQL(
            "INSERT INTO nextcloud_collection_bindings (collectionId, remoteHref, remoteTitle, remoteEtag, remoteLogicalClock, updatedAt, remoteWritable, syncEnabled, lastFailureCode, lastFailureAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("collection-1", "https://cloud.example/tasks/shared/", "Shared", null, 1L, 1L, 0, 1, null, null),
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(testDbName, 57, true, KernelDatabase.MIGRATION_56_57)
        db.query("SELECT remoteWritable, remoteAvailable, blockedUnsyncedAt FROM nextcloud_collection_bindings WHERE collectionId = 'collection-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("remoteWritable")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("remoteAvailable")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("blockedUnsyncedAt")))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migration 57 to 58 adds manual sort default without changing lists`() {
        val oldDb = helper.createDatabase(testDbName, 57)
        oldDb.execSQL(
            """
            INSERT INTO lists (
                name, createdAt, updatedAt, pinned, displayOrder, archivedAt, collectionId,
                canonicalTitle, localDisplayAlias, lifecycle, titleLogicalClock, titleStampActorId,
                lifecycleLogicalClock, lifecycleStampActorId
            ) VALUES ('Recipe', 1000, 1001, 0, 0, NULL, 'collection-1', 'Recipe', NULL,
                      'ACTIVE', 1, 'test', 1, 'test')
            """.trimIndent(),
        )
        oldDb.close()

        val db = helper.runMigrationsAndValidate(testDbName, 58, true, KernelDatabase.MIGRATION_57_58)
        db.query("SELECT name, manualItemSortByDefault FROM lists WHERE collectionId = 'collection-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Recipe", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("manualItemSortByDefault")))
        }
        db.close()
    }
}
