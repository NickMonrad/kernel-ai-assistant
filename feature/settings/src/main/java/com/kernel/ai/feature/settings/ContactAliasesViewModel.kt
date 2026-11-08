package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactAliasesViewModel @Inject constructor(
    private val repository: ContactAliasRepository,
) : ViewModel() {

    val aliases: StateFlow<List<ContactAliasEntity>> = repository.getAllAliases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteAlias(alias: String) {
        viewModelScope.launch { repository.deleteAlias(alias) }
    }

    fun addAlias(alias: String, displayName: String, contactId: String, phoneNumber: String) {
        viewModelScope.launch { repository.addAlias(alias, displayName, contactId, phoneNumber) }
    }
}
