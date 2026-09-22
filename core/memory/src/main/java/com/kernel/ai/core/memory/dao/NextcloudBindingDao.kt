package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity

@Dao
interface NextcloudCollectionBindingDao {
    @Query("SELECT * FROM nextcloud_collection_bindings WHERE collectionId = :collectionId LIMIT 1")
    suspend fun get(collectionId: String): NextcloudCollectionBindingEntity?
    @Query("SELECT * FROM nextcloud_collection_bindings WHERE remoteHref = :remoteHref LIMIT 1")
    suspend fun getByRemoteHref(remoteHref: String): NextcloudCollectionBindingEntity?

    @Query("SELECT * FROM nextcloud_collection_bindings ORDER BY updatedAt ASC")
    suspend fun getAll(): List<NextcloudCollectionBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: NextcloudCollectionBindingEntity)

    @Query("DELETE FROM nextcloud_collection_bindings WHERE collectionId = :collectionId")
    suspend fun delete(collectionId: String)
}

@Dao
interface NextcloudItemBindingDao {
    @Query("SELECT * FROM nextcloud_item_bindings WHERE itemId = :itemId LIMIT 1")
    suspend fun get(itemId: String): NextcloudItemBindingEntity?

    @Query("SELECT * FROM nextcloud_item_bindings WHERE collectionId = :collectionId")
    suspend fun getAll(collectionId: String): List<NextcloudItemBindingEntity>

    @Query("SELECT * FROM nextcloud_item_bindings WHERE collectionId = :collectionId AND remoteUid = :remoteUid LIMIT 1")
    suspend fun getByRemoteUid(collectionId: String, remoteUid: String): NextcloudItemBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: NextcloudItemBindingEntity)

    @Query("DELETE FROM nextcloud_item_bindings WHERE itemId = :itemId")
    suspend fun delete(itemId: String)
}
