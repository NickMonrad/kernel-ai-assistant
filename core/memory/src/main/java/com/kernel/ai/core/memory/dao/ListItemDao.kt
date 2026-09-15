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

    @Query("SELECT * FROM list_items WHERE listId = :listId AND lifecycle = 'ACTIVE' AND checked = 0 ORDER BY CAST(orderKey AS REAL) ASC, itemId ASC")
    suspend fun getByList(listId: Long): List<ListItemEntity>

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' ORDER BY li.checked ASC, CAST(li.orderKey AS REAL) ASC, li.itemId ASC")
    fun observeByList(listId: Long): Flow<List<ListItemEntity>>

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' ORDER BY li.checked ASC, CAST(li.orderKey AS REAL) ASC, li.itemId ASC")
    suspend fun getAllByList(listId: Long): List<ListItemEntity>

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' ORDER BY li.listId ASC, li.checked ASC, CAST(li.orderKey AS REAL) ASC, li.itemId ASC")
    fun observeAll(): Flow<List<ListItemEntity>>

    @Query("SELECT * FROM list_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ListItemEntity?

    @Query("SELECT * FROM list_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getByItemId(itemId: String): ListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ListItemEntity)

    @Query("UPDATE list_items SET checked = 1 WHERE id = :id")
    suspend fun markChecked(id: Long)

    @Query("UPDATE list_items SET checked = 0 WHERE id = :id")
    suspend fun markUnchecked(id: Long)

    @Query("UPDATE list_items SET checked = :checked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean, updatedAt: Long)

    @Query("UPDATE list_items SET text = :text, dueAt = :dueAt, isFavourite = :isFavourite, notificationTime = :notificationTime, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItem(id: Long, text: String, dueAt: Long?, isFavourite: Boolean, notificationTime: Long?, updatedAt: Long)

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' AND li.checked = 1 AND li.notificationTime IS NOT NULL")
    suspend fun getCheckedWithNotification(listId: Long): List<ListItemEntity>

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' AND li.notificationTime IS NOT NULL")
    suspend fun getAllWithNotification(listId: Long): List<ListItemEntity>

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE' AND li.notificationTime IS NOT NULL AND li.checked = 0")
    suspend fun getAllActiveWithNotification(): List<ListItemEntity>

    /** Legacy physical-delete helpers are retained for tests/maintenance; sync paths tombstone. */
    @Query("DELETE FROM list_items WHERE listId = :listId AND checked = 1")
    suspend fun deleteChecked(listId: Long)

    @Query("DELETE FROM list_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("UPDATE list_items SET checked = NOT checked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleChecked(id: Long, updatedAt: Long)

    @Query("UPDATE list_items SET isFavourite = NOT isFavourite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleFavourite(id: Long, updatedAt: Long)

    @Query("UPDATE list_items SET isFavourite = :fav, updatedAt = :now WHERE id = :id")
    suspend fun setFavourite(id: Long, fav: Boolean, now: Long)

    @Query("UPDATE list_items SET displayOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItemOrder(id: Long, order: Long, updatedAt: Long)

    @Transaction
    suspend fun replaceItemOrders(updates: List<Pair<Long, Long>>, now: Long) {
        updates.forEach { (id, order) -> updateItemOrder(id, order, now) }
    }
}
