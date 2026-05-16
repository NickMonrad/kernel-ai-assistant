package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ListItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ListItemEntity)

    /** Unchecked items for a given list, oldest first. */
    @Query("SELECT * FROM list_items WHERE listId = :listId AND checked = 0 ORDER BY createdAt ASC")
    suspend fun getByList(listId: Long): List<ListItemEntity>

    /** All items for a given list (checked + unchecked), for display purposes. */
    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY checked ASC, createdAt ASC")
    fun observeByList(listId: Long): Flow<List<ListItemEntity>>

    /** All items across all lists, for grouped display. */
    @Query("SELECT * FROM list_items ORDER BY listId ASC, checked ASC, createdAt ASC")
    fun observeAll(): Flow<List<ListItemEntity>>

    @Query("UPDATE list_items SET checked = 1 WHERE id = :id")
    suspend fun markChecked(id: Long)

    @Query("UPDATE list_items SET checked = 0 WHERE id = :id")
    suspend fun markUnchecked(id: Long)

    /** Set checked state and bump updatedAt in one round-trip. */
    @Query("UPDATE list_items SET checked = :checked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean, updatedAt: Long)

    @Query("UPDATE list_items SET text = :text, dueAt = :dueAt, isFavourite = :isFavourite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItem(id: Long, text: String, dueAt: Long?, isFavourite: Boolean, updatedAt: Long)

    /** Remove all checked items from a list. */
    @Query("DELETE FROM list_items WHERE listId = :listId AND checked = 1")
    suspend fun deleteChecked(listId: Long)

    /** Remove a single item by id. */
    @Query("DELETE FROM list_items WHERE id = :id")
    suspend fun deleteItem(id: Long)
}
