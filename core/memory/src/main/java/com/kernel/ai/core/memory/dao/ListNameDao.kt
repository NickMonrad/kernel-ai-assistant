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

    /**
     * Inserts a new list and returns the generated row-id (= entity id), or -1 on a
     * name-unique conflict (IGNORE strategy).  Callers can fall back to [getByName] when -1
     * is returned to handle the already-exists case.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAndGet(list: ListNameEntity): Long

    @Query("SELECT * FROM lists ORDER BY pinned DESC, createdAt ASC")
    abstract fun observeAll(): Flow<List<ListNameEntity>>

    @Query("SELECT * FROM lists ORDER BY pinned DESC, createdAt ASC")
    abstract suspend fun getAll(): List<ListNameEntity>

    /** Resolve a list by display name — used by NativeIntentHandler at the skill boundary. */
    @Query("SELECT * FROM lists WHERE name = :name LIMIT 1")
    abstract suspend fun getByName(name: String): ListNameEntity?

    @Query("DELETE FROM lists WHERE name = :name")
    abstract suspend fun deleteByName(name: String)

    @Query("DELETE FROM lists WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE lists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateName(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE lists SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updatePinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE lists SET pinned = NOT pinned, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun togglePinned(id: Long, updatedAt: Long)

    @Query("UPDATE lists SET updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateTimestamp(id: Long, updatedAt: Long)

    @Query("UPDATE lists SET displayOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateDisplayOrder(id: Long, order: Int, updatedAt: Long)

    @Transaction
    open suspend fun updateDisplayOrders(updates: List<Pair<Long, Int>>, updatedAt: Long) {
        updates.forEach { (id, order) -> updateDisplayOrder(id, order, updatedAt) }
    }
}
