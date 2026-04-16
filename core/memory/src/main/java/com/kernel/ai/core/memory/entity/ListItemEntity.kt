package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "list_items")
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listName: String,
    val item: String,
    val addedAt: Long = System.currentTimeMillis(),
    val checked: Boolean = false,
)
