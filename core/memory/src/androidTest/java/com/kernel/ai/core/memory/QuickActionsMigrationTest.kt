package com.kernel.ai.core.memory

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private val testDbName = "quick-actions-migration-test.db"

    /**
     * 58 -> 59 adds a nullable spokenSummary column to quick_actions (#1026). runMigrationsAndValidate
     * checks the migrated schema against the exported 59.json, so a missing or mistyped column fails here.
     */
    @Test
    fun `migration 58 to 59 adds nullable spokenSummary to quick_actions`() {
        helper.createDatabase(testDbName, 58).close()

        helper.runMigrationsAndValidate(
            testDbName,
            59,
            true,
            KernelDatabase.MIGRATION_58_59,
        ).close()
    }
}
