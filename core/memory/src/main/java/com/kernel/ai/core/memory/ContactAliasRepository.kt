package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.ContactAliasDao
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactAliasRepository @Inject constructor(private val dao: ContactAliasDao) {
    fun getAllAliases(): Flow<List<ContactAliasEntity>> = dao.getAllAliases()

    suspend fun getByAlias(alias: String): ContactAliasEntity? =
        dao.getByAlias(alias.trim().lowercase())

    suspend fun addAlias(
        alias: String,
        displayName: String,
        contactId: String,
        phoneNumber: String,
    ) {
        dao.insert(ContactAliasEntity(alias.trim().lowercase(), displayName, contactId, phoneNumber))
    }

    suspend fun deleteAlias(alias: String) = dao.delete(alias.trim().lowercase())
}
