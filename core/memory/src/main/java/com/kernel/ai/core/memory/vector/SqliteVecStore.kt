package com.kernel.ai.core.memory.vector

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SqliteVecStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : VectorStore {

    private val handle: Long by lazy { openDb() }

    private fun openDb(): Long {
        val version = try {
            VectorStoreJni.getVersion()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load kernelvec JNI lib", e)
            return 0L
        }
        Log.i(TAG, "sqlite-vec $version loaded")

        val dbFile = File(context.filesDir, "kernel_vectors.db")
        val h = VectorStoreJni.openDatabase(dbFile.absolutePath)
        if (h == 0L) Log.e(TAG, "Failed to open vector database")
        return h
    }

    override fun createTable(tableName: String, dimensions: Int) {
        execOrThrow(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $tableName USING vec0(embedding float[$dimensions])"
        )
    }

    override fun upsert(tableName: String, rowId: Long, embedding: FloatArray) {
        VectorStoreJni.upsert(handle, tableName, rowId, embedding)
            ?.let { error("VectorStore upsert failed: $it") }
    }

    override fun delete(tableName: String, rowId: Long) {
        VectorStoreJni.deleteRow(handle, tableName, rowId)
            ?.let { error("VectorStore delete failed: $it") }
    }

    override fun search(tableName: String, query: FloatArray, k: Int): List<VectorSearchResult> {
        val raw = VectorStoreJni.search(handle, tableName, query, k)
        return buildList {
            var i = 0
            while (i < raw.size) {
                add(VectorSearchResult(rowId = raw[i].toLong(), distance = raw[i + 1].toFloat()))
                i += 2
            }
        }
    }

    override fun dropTable(tableName: String) {
        execOrThrow("DROP TABLE IF EXISTS $tableName")
    }

    private fun execOrThrow(sql: String) {
        VectorStoreJni.exec(handle, sql)
            ?.let { error("VectorStore exec failed: $it\nSQL: $sql") }
    }

    companion object {
        private const val TAG = "SqliteVecStore"
    }
}

