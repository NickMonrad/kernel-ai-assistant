package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ListNameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListNameDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(list: ListNameEntity)

    /**
     * Inserts a new list and returns the generated row-id (= entity id), or -1 on a
     * name-unique conflict (IGNORE strategy).  Callers can fall back to [getByName] when -1
     * is returned to handle the already-exists case.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAndGet(list: ListNameEntity): Long

    @Query("SELECT * FROM lists ORDER BY pinned DESC, createdAt ASC")
    fun observeAll(): Flow<List<ListNameEntity>>

    @Query("SELECT * FROM lists ORDER BY pinned DESC, createdAt ASC")
    suspend fun getAll(): List<ListNameEntity>

    /** Resolve a list by display name — used by NativeIntentHandler at the skill boundary. */
    @Query("SELECT * FROM lists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ListNameEntity?

    @Query("DELETE FROM lists WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE lists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE lists SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE lists SET pinned = NOT pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun togglePinned(id: Long, updatedAt: Long)

    @Query("UPDATE lists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTimestamp(id: Long, updatedAt: Long)
}
