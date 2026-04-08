package com.kernel.ai.core.memory.vector

interface VectorStore {
    /** Create the vec0 virtual table if it doesn't exist. */
    fun createTable(tableName: String, dimensions: Int)

    /** Insert or replace a vector for the given rowId. */
    fun upsert(tableName: String, rowId: Long, embedding: FloatArray)

    /** Delete the vector with the given rowId. */
    fun delete(tableName: String, rowId: Long)

    /** Return the k nearest neighbours to the query embedding. */
    fun search(tableName: String, query: FloatArray, k: Int): List<VectorSearchResult>

    /** Drop the vec0 virtual table. */
    fun dropTable(tableName: String)
}
