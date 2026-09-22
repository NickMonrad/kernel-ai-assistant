package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index

/** Provider metadata for one explicit local-list ↔ Nextcloud collection binding. */
@Entity(
    tableName = "nextcloud_collection_bindings",
    indices = [Index(value = ["remoteHref"], unique = true)],
)
data class NextcloudCollectionBindingEntity(
    @androidx.room.PrimaryKey val collectionId: String,
    val remoteHref: String,
    val remoteTitle: String,
    val remoteEtag: String?,
    val remoteLogicalClock: Long,
    val updatedAt: Long,
    /**
     * Per-list "Stop Nextcloud sync" state (#1551). False keeps the binding, the local list and the
     * remote collection, and only suspends synchronization for this list.
     */
    val syncEnabled: Boolean = true,
    /** Last sync failure category for this list, or null when the last synchronization succeeded. */
    val lastFailureCode: String? = null,
    val lastFailureAt: Long? = null,
)

/** Provider metadata for one item; local itemId remains the authoritative identity. */
@Entity(
    tableName = "nextcloud_item_bindings",
    indices = [
        Index(value = ["collectionId"]),
        Index(value = ["collectionId", "remoteUid"], unique = true),
    ],
)
data class NextcloudItemBindingEntity(
    @androidx.room.PrimaryKey val itemId: String,
    val collectionId: String,
    val remoteUid: String,
    val remoteHref: String,
    val etag: String?,
    /** Last complete VTODO document, retained so unknown properties survive known-field edits. */
    val rawVtodo: String,
    val remoteLogicalClock: Long,
    val deletedRemotely: Boolean,
    val updatedAt: Long,
)
