package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lists",
    indices = [Index(value = ["name"], unique = true)],
)
data class ListNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Bumped on rename and whenever any child item changes. */
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** Manual drag-and-drop order within the pinned or unpinned group. */
    @ColumnInfo(name = "displayOrder") val displayOrder: Int = 0,
)
