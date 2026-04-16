package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_aliases")
data class ContactAliasEntity(
    @PrimaryKey val alias: String,      // lowercase, trimmed — e.g. "mum", "wife"
    val displayName: String,            // UI display — e.g. "Margaret Smith"
    val contactId: String,              // Android ContactsContract ID
    val phoneNumber: String,            // cached phone number
)
