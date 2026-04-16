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

    @Query("SELECT * FROM lists ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ListNameEntity>>

    @Query("SELECT * FROM lists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ListNameEntity?

    @Query("DELETE FROM lists WHERE name = :name")
    suspend fun deleteByName(name: String)
}
