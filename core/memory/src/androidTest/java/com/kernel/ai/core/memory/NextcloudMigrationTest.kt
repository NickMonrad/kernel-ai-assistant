package com.kernel.ai.core.memory

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextcloudMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "nextcloud-migration.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    @Test
    fun migration52To53CreatesProviderBindingTablesAndHrefUniqueness() {
        helper.createDatabase(testDbName, 52).close()
        val db = helper.runMigrationsAndValidate(
            testDbName,
            53,
            true,
            KernelDatabase.MIGRATION_52_53,
        )

        db.execSQL(
            "INSERT INTO nextcloud_collection_bindings(collectionId, remoteHref, remoteTitle, remoteLogicalClock, updatedAt) VALUES ('local-1', '/tasks/', 'Tasks', 1, 1)",
        )
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO nextcloud_collection_bindings(collectionId, remoteHref, remoteTitle, remoteLogicalClock, updatedAt) VALUES ('local-2', '/tasks/', 'Other', 1, 1)",
            )
        }
        assertTrue(tableExists(db, "nextcloud_item_bindings"))
        db.close()
    }

    @Test
    fun migration53To54AddsPerListSyncLifecycleState() {
        helper.createDatabase(testDbName, 53).close()
        val db = helper.runMigrationsAndValidate(
            testDbName,
            54,
            true,
            KernelDatabase.MIGRATION_53_54,
        )

        db.execSQL(
            "INSERT INTO nextcloud_collection_bindings(collectionId, remoteHref, remoteTitle, remoteLogicalClock, updatedAt) VALUES ('local-1', '/tasks/', 'Tasks', 1, 1)",
        )
        db.query(
            "SELECT syncEnabled, lastFailureCode, lastFailureAt FROM nextcloud_collection_bindings WHERE collectionId = 'local-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        db.close()
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
}
