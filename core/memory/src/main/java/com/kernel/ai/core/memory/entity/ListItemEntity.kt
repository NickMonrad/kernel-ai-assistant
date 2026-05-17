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
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["listId"])],
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** FK referencing lists.id — replaces the old listName string. */
    val listId: Long,
    /** The item text (was: item). */
    val text: String,
    /** Creation timestamp (was: addedAt). */
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val checked: Boolean = false,
    val dueAt: Long? = null,
    val isFavourite: Boolean = false,
    /** Epoch-ms when a notification should fire, or null for no notification. */
    val notificationTime: Long? = null,
    /** Position index for MANUAL sort order; initialised to id on migration 38→39. */
    val displayOrder: Long = 0L,
)
