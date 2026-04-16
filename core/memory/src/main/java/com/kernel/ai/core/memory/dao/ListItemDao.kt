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
    @Query("SELECT * FROM list_items WHERE listName = :listName AND checked = 0 ORDER BY addedAt ASC")
    suspend fun getByList(listName: String): List<ListItemEntity>

    /** All items for a given list (checked + unchecked), for display purposes. */
    @Query("SELECT * FROM list_items WHERE listName = :listName ORDER BY checked ASC, addedAt ASC")
    fun observeByList(listName: String): Flow<List<ListItemEntity>>

    /** Distinct list names that have at least one item. */
    @Query("SELECT DISTINCT listName FROM list_items ORDER BY listName ASC")
    suspend fun getAllLists(): List<String>

    /** Observe all list names reactively. */
    @Query("SELECT DISTINCT listName FROM list_items ORDER BY listName ASC")
    fun observeAllLists(): Flow<List<String>>

    /** All items across all lists, for grouped display. */
    @Query("SELECT * FROM list_items ORDER BY listName ASC, checked ASC, addedAt ASC")
    fun observeAll(): Flow<List<ListItemEntity>>

    @Query("UPDATE list_items SET checked = 1 WHERE id = :id")
    suspend fun markChecked(id: Long)

    @Query("UPDATE list_items SET checked = 0 WHERE id = :id")
    suspend fun markUnchecked(id: Long)

    /** Remove all checked items from a list. */
    @Query("DELETE FROM list_items WHERE listName = :listName AND checked = 1")
    suspend fun deleteChecked(listName: String)

    /** Remove the entire list. */
    @Query("DELETE FROM list_items WHERE listName = :listName")
    suspend fun deleteList(listName: String)
}
