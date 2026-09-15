package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "list_items",
    foreignKeys = [
        ForeignKey(
            entity = ListNameEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["listId"]),
        Index(value = ["itemId"], unique = true),
        Index(value = ["collectionId"]),
    ],
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local FK; never leaves the device as identity. */
    val listId: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val checked: Boolean = false,
    val dueAt: Long? = null,
    val isFavourite: Boolean = false,
    val notificationTime: Long? = null,
    /** Local compatibility/display order; [orderKey] is sync authority. */
    val displayOrder: Long = 0L,
    val itemId: String = java.util.UUID.randomUUID().toString(),
    val collectionId: String = "",
    val parentItemId: String? = null,
    val orderKey: String = "0",
    val textLogicalClock: Long = 0L,
    val textStampActorId: String = "",
    val checkedLogicalClock: Long = 0L,
    val checkedStampActorId: String = "",
    val dueAtLogicalClock: Long = 0L,
    val dueAtStampActorId: String = "",
    val placementLogicalClock: Long = 0L,
    val placementStampActorId: String = "",
    val lifecycle: String = "ACTIVE",
    val lifecycleLogicalClock: Long = 0L,
    val lifecycleStampActorId: String = "",
)
