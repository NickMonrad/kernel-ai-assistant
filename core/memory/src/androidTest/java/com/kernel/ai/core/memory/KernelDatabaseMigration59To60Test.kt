package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KernelDatabaseMigration59To60Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "kernel-59-60-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    /**
     * 59 -> 60 (#1961) rewrites only model_settings rows at the old MID_RANGE defaults (3072 and the
     * pre-#186 4096) to 7000. Rows at other sizes, including 4000 (the slider minimum) and user-chosen
     * values, are untouched. runMigrationsAndValidate checks the schema against the exported 60.json.
     */
    @Test
    fun `migration 59 to 60 rewrites only rows at the old 3072 and 4096 defaults`() {
        helper.createDatabase(testDbName, 59).apply {
            listOf(
                "model-at-old-default" to 3072,
                "model-at-pre-186-default" to 4096,
                "model-at-slider-minimum" to 4000,
                "model-user-4500" to 4500,
                "model-user-8000" to 8000,
            ).forEach { (modelId, size) ->
                execSQL(
                    "INSERT INTO model_settings (modelId, contextWindowSize, temperature, topP, topK, " +
                        "showThinkingProcess, speculativeDecodingEnabled, updatedAt) " +
                        "VALUES ('$modelId', $size, 1.0, 0.95, 64, 0, 0, 1700000000000)",
                )
            }
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            60,
            true,
            KernelDatabase.MIGRATION_59_60,
        )
        val sizes = mutableMapOf<String, Int>()
        db.query("SELECT modelId, contextWindowSize FROM model_settings").use { cursor ->
            while (cursor.moveToNext()) {
                sizes[cursor.getString(0)] = cursor.getInt(1)
            }
        }
        db.close()

        assertEquals(
            mapOf(
                "model-at-old-default" to 7000,
                "model-at-pre-186-default" to 7000,
                "model-at-slider-minimum" to 4000,
                "model-user-4500" to 4500,
                "model-user-8000" to 8000,
            ),
            sizes,
        )
    }
}
