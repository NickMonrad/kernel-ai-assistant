package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity
import com.kernel.ai.core.memory.nextcloud.NextcloudListSyncSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface NextcloudCollectionBindingDao {
    @Query("SELECT * FROM nextcloud_collection_bindings WHERE collectionId = :collectionId LIMIT 1")
    suspend fun get(collectionId: String): NextcloudCollectionBindingEntity?
    @Query("SELECT * FROM nextcloud_collection_bindings WHERE remoteHref = :remoteHref LIMIT 1")
    suspend fun getByRemoteHref(remoteHref: String): NextcloudCollectionBindingEntity?

    @Query("SELECT * FROM nextcloud_collection_bindings ORDER BY updatedAt ASC")
    suspend fun getAll(): List<NextcloudCollectionBindingEntity>

    /** Per-list sync state for the Lists overview and the Nextcloud lists screen (#1551). */
    @Query("SELECT collectionId, remoteHref, syncEnabled, lastFailureCode FROM nextcloud_collection_bindings")
    fun observeSyncSummaries(): Flow<List<NextcloudListSyncSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: NextcloudCollectionBindingEntity)

    /** Stop or resume synchronization for one list without touching its remote association. */
    @Query("UPDATE nextcloud_collection_bindings SET syncEnabled = :enabled, updatedAt = :updatedAt WHERE collectionId = :collectionId")
    suspend fun setSyncEnabled(collectionId: String, enabled: Boolean, updatedAt: Long)

    /** Records the last sync outcome; a null [failureCode] clears a previous failure. */
    @Query("UPDATE nextcloud_collection_bindings SET lastFailureCode = :failureCode, lastFailureAt = :failedAt WHERE collectionId = :collectionId")
    suspend fun recordSyncOutcome(collectionId: String, failureCode: String?, failedAt: Long?)

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
