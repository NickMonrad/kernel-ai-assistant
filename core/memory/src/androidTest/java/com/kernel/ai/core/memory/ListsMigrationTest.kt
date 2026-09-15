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

}
