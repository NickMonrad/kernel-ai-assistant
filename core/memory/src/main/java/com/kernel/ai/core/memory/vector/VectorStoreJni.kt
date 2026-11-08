package com.kernel.ai.core.memory.vector

/** Thin JNI bridge to the bundled SQLite + sqlite-vec native library. */
internal object VectorStoreJni {
    init {
        System.loadLibrary("kernelvec")
    }

    external fun getVersion(): String

    /** Opens (or creates) a SQLite database at [path]. Returns a native handle (sqlite3*). */
    external fun openDatabase(path: String): Long

    /** Closes the database opened by [openDatabase]. */
    external fun closeDatabase(handle: Long)

    /**
     * Executes a raw SQL statement. Returns null on success, or an error message on failure.
     */
    external fun exec(handle: Long, sql: String): String?

    /**
     * Inserts or replaces a vector for [rowId]. Returns null on success, or an error message.
     */
    external fun upsert(handle: Long, table: String, rowId: Long, embedding: FloatArray): String?

    /**
     * Deletes the vector with [rowId]. Returns null on success, or an error message.
     */
    external fun deleteRow(handle: Long, table: String, rowId: Long): String?

    /**
     * Returns the k nearest neighbours as a flat [rowId0, dist0, rowId1, dist1, ...] DoubleArray.
     */
    external fun search(handle: Long, table: String, query: FloatArray, k: Int): DoubleArray
}
