package com.kernel.ai.core.memory.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteVecCosineAndroidTest {
    @Test
    fun `search distances use cosine metric`() {
        val store = SqliteVecStore(ApplicationProvider.getApplicationContext<Context>())
        val table = "cosine_contract_${UUID.randomUUID().toString().replace('-', '_')}"

        try {
            store.createTable(table, dimensions = 3)
            store.upsert(table, 1L, floatArrayOf(1f, 0f, 0f))
            store.upsert(table, 2L, floatArrayOf(0f, 1f, 0f))

            val distances = store.search(table, floatArrayOf(1f, 0f, 0f), k = 2).associate { it.rowId to it.distance }
            assertEquals(0f, distances.getValue(1L), 1e-5f)
            assertEquals(1f, distances.getValue(2L), 1e-5f)
        } finally {
            store.dropTable(table)
        }
    }
}
