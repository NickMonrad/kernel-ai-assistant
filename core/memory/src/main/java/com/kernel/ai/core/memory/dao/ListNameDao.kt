package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kernel.ai.core.memory.entity.ListNameEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ListNameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(list: ListNameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(list: ListNameEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAndGet(list: ListNameEntity): Long

    @Query("SELECT * FROM lists WHERE lifecycle = 'ACTIVE' ORDER BY pinned DESC, createdAt ASC")
    abstract fun observeAll(): Flow<List<ListNameEntity>>

    /** Active and archived lists; synchronized tombstones are excluded. */
    @Query("SELECT * FROM lists WHERE lifecycle = 'ACTIVE' AND archivedAt IS NULL ORDER BY pinned DESC, createdAt ASC")
    abstract fun observeActiveLists(): Flow<List<ListNameEntity>>

    @Query("SELECT * FROM lists WHERE lifecycle = 'ACTIVE' ORDER BY pinned DESC, createdAt ASC")
    abstract suspend fun getAll(): List<ListNameEntity>

    @Query("SELECT * FROM lists WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: Long): ListNameEntity?

    @Query("SELECT * FROM lists WHERE collectionId = :collectionId LIMIT 1")
    abstract suspend fun getByCollectionId(collectionId: String): ListNameEntity?

    /** Resolves the effective local display name for user-facing callers. */
    @Query("SELECT * FROM lists WHERE lifecycle = 'ACTIVE' AND name = :name LIMIT 1")
    abstract suspend fun getByName(name: String): ListNameEntity?

    @Query("SELECT * FROM lists WHERE name = :name LIMIT 1")
    abstract suspend fun getByNameAnyLifecycle(name: String): ListNameEntity?

    /** Retained for non-sync legacy callers; sync-aware paths use tombstones. */
    @Query("DELETE FROM lists WHERE name = :name")
    abstract suspend fun deleteByName(name: String)

    @Query("DELETE FROM lists WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE lists SET name = :name, canonicalTitle = :canonicalTitle, localDisplayAlias = :localDisplayAlias, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateName(id: Long, name: String, canonicalTitle: String, localDisplayAlias: String?, updatedAt: Long)

    @Query("UPDATE lists SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updatePinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE lists SET pinned = NOT pinned, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun togglePinned(id: Long, updatedAt: Long)

    @Query("UPDATE lists SET updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateTimestamp(id: Long, updatedAt: Long)

    @Query("UPDATE lists SET displayOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateDisplayOrder(id: Long, order: Int, updatedAt: Long)

    @Query("UPDATE lists SET archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun archiveList(id: Long, archivedAt: Long, updatedAt: Long)

    @Query("UPDATE lists SET archivedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun restoreList(id: Long, updatedAt: Long)

    @Query("SELECT * FROM lists WHERE lifecycle = 'ACTIVE' AND archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    abstract fun observeArchivedLists(): Flow<List<ListNameEntity>>

    @Transaction
    open suspend fun updateDisplayOrders(updates: List<Pair<Long, Int>>, updatedAt: Long) {
        updates.forEach { (id, order) -> updateDisplayOrder(id, order, updatedAt) }
    }
}
