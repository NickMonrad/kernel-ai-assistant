package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** All items for a given list as a one-shot query — active first, then completed, both asc by creation. */
    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY checked ASC, createdAt ASC")
    suspend fun getAllByList(listId: Long): List<ListItemEntity>

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

    @Query("UPDATE list_items SET text = :text, dueAt = :dueAt, isFavourite = :isFavourite, notificationTime = :notificationTime, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItem(id: Long, text: String, dueAt: Long?, isFavourite: Boolean, notificationTime: Long?, updatedAt: Long)

    /** Checked items with a pending notification — used by clearChecked() to cancel alarms first. */
    @Query("SELECT * FROM list_items WHERE listId = :listId AND checked = 1 AND notificationTime IS NOT NULL")
    suspend fun getCheckedWithNotification(listId: Long): List<ListItemEntity>

    /** All items in a list (any checked state) that have a scheduled notification. */
    @Query("SELECT * FROM list_items WHERE listId = :listId AND notificationTime IS NOT NULL")
    suspend fun getAllWithNotification(listId: Long): List<ListItemEntity>

    /** All unchecked items across all lists with a scheduled notification — used on device reboot. */
    @Query("SELECT * FROM list_items WHERE notificationTime IS NOT NULL AND checked = 0")
    suspend fun getAllActiveWithNotification(): List<ListItemEntity>

    /** Remove all checked items from a list. */
    @Query("DELETE FROM list_items WHERE listId = :listId AND checked = 1")
    suspend fun deleteChecked(listId: Long)

    /** Remove a single item by id. */
    @Query("DELETE FROM list_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    /** Atomically flip checked and bump updatedAt — avoids TOCTOU on rapid taps. */
    @Query("UPDATE list_items SET checked = NOT checked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleChecked(id: Long, updatedAt: Long)

    /** Atomically flip isFavourite and bump updatedAt — avoids TOCTOU on rapid taps. */
    @Query("UPDATE list_items SET isFavourite = NOT isFavourite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleFavourite(id: Long, updatedAt: Long)

    /** Set isFavourite to an explicit value and bump updatedAt — used by bulk-favourite (#917). */
    @Query("UPDATE list_items SET isFavourite = :fav, updatedAt = :now WHERE id = :id")
    suspend fun setFavourite(id: Long, fav: Boolean, now: Long)

    /** Update the displayOrder for manual drag-to-reorder (#917). */
    @Query("UPDATE list_items SET displayOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItemOrder(id: Long, order: Long, updatedAt: Long)

    /** Atomically persist a full reorder in one transaction (#917). */
    @Transaction
    suspend fun replaceItemOrders(updates: List<Pair<Long, Long>>, now: Long) {
        updates.forEach { (id, order) -> updateItemOrder(id, order, now) }
    }
}
