package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickActionsMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "quick-actions-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    /**
     * 58 -> 59 adds a nullable spokenSummary column to quick_actions (#1026). runMigrationsAndValidate
     * checks the schema against the exported 59.json. The row check proves existing cards keep their
     * data and read back with spokenSummary = NULL.
     */
    @Test
    fun `migration 58 to 59 keeps existing quick action rows and leaves spokenSummary null`() {
        helper.createDatabase(testDbName, 58).apply {
            execSQL(
                "INSERT INTO quick_actions (id, userQuery, skillName, resultText, isSuccess, presentationJson, timestamp) " +
                    "VALUES ('card-1', 'weather', 'get_weather', 'Brisbane 22C', 1, '{\"type\":\"weather\"}', 1700000000000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            59,
            true,
            KernelDatabase.MIGRATION_58_59,
        )
        db.query(
            "SELECT userQuery, skillName, resultText, isSuccess, presentationJson, timestamp, spokenSummary " +
                "FROM quick_actions WHERE id = 'card-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("weather", cursor.getString(0))
            assertEquals("get_weather", cursor.getString(1))
            assertEquals("Brisbane 22C", cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals("{\"type\":\"weather\"}", cursor.getString(4))
            assertEquals(1700000000000L, cursor.getLong(5))
            assertTrue(cursor.isNull(6))
            assertFalse(cursor.moveToNext())
        }
        db.close()
    }
}
