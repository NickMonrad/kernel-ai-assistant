package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactAliasDao {
    @Query("SELECT * FROM contact_aliases ORDER BY alias ASC")
    fun getAllAliases(): Flow<List<ContactAliasEntity>>

    @Query("SELECT * FROM contact_aliases WHERE alias = :alias LIMIT 1")
    suspend fun getByAlias(alias: String): ContactAliasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alias: ContactAliasEntity)

    @Query("DELETE FROM contact_aliases WHERE alias = :alias")
    suspend fun delete(alias: String)
}
