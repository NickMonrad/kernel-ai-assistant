package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.lists.OrderKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
@Dao
interface ListItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ListItemEntity)

    @Query("SELECT * FROM list_items WHERE listId = :listId AND lifecycle = 'ACTIVE' AND checked = 0")
    suspend fun getByListUnordered(listId: Long): List<ListItemEntity>

    suspend fun getByList(listId: Long): List<ListItemEntity> =
        getByListUnordered(listId).sortedWith(EFFECTIVE_ORDER_COMPARATOR)

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE'")
    fun observeByListUnordered(listId: Long): Flow<List<ListItemEntity>>

    fun observeByList(listId: Long): Flow<List<ListItemEntity>> =
        observeByListUnordered(listId).map { it.sortedWith(EFFECTIVE_GROUPED_ORDER_COMPARATOR) }

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.listId = :listId AND li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE'")
    suspend fun getAllByListUnordered(listId: Long): List<ListItemEntity>

    suspend fun getAllByList(listId: Long): List<ListItemEntity> =
        getAllByListUnordered(listId).sortedWith(EFFECTIVE_GROUPED_ORDER_COMPARATOR)

    @Query("SELECT li.* FROM list_items li INNER JOIN lists l ON li.listId = l.id WHERE li.lifecycle = 'ACTIVE' AND l.lifecycle = 'ACTIVE'")
    fun observeAllUnordered(): Flow<List<ListItemEntity>>

    fun observeAll(): Flow<List<ListItemEntity>> =
        observeAllUnordered().map { it.sortedWith(EFFECTIVE_GLOBAL_ORDER_COMPARATOR) }

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

    @Query("UPDATE list_items SET displayOrder = :order WHERE id = :id")
    suspend fun updateDisplayOrderProjection(id: Long, order: Long)

    @Query("UPDATE list_items SET displayOrder = :order, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateItemOrder(id: Long, order: Long, updatedAt: Long)

    @Transaction
    suspend fun replaceItemOrders(updates: List<Pair<Long, Long>>, now: Long) {
        updates.forEach { (id, order) -> updateItemOrder(id, order, now) }
    }
}


private val EFFECTIVE_ORDER_COMPARATOR = Comparator<ListItemEntity> { left, right ->
    OrderKey.compare(left.orderKey, right.orderKey)
        .takeIf { it != 0 }
        ?: left.itemId.compareTo(right.itemId).takeIf { it != 0 }
        ?: left.id.compareTo(right.id)
}

private val EFFECTIVE_GROUPED_ORDER_COMPARATOR = Comparator<ListItemEntity> { left, right ->
    left.checked.compareTo(right.checked).takeIf { it != 0 }
        ?: EFFECTIVE_ORDER_COMPARATOR.compare(left, right)
}

private val EFFECTIVE_GLOBAL_ORDER_COMPARATOR = Comparator<ListItemEntity> { left, right ->
    left.listId.compareTo(right.listId).takeIf { it != 0 }
        ?: EFFECTIVE_GROUPED_ORDER_COMPARATOR.compare(left, right)
}