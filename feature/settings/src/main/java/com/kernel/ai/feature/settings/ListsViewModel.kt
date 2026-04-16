package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val dao: ListItemDao,
    private val listNameDao: ListNameDao,
) : ViewModel() {

    /** All items grouped by list name — used by the drill-in item screen. */
    val groupedItems: StateFlow<Map<String, List<ListItemEntity>>> =
        dao.observeAll()
            .map { items -> items.groupBy { it.listName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** List names from the lists table — persists even when lists are empty. */
    val listNames: StateFlow<List<String>> =
        listNameDao.observeAll()
            .map { it.map { e -> e.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Item counts keyed by list name — shown in the overview. */
    val itemCounts: StateFlow<Map<String, Int>> =
        dao.observeAll()
            .map { items -> items.groupBy { it.listName }.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Search query for the list overview screen. */
    private val _listSearchQuery = MutableStateFlow("")
    val listSearchQuery: StateFlow<String> = _listSearchQuery.asStateFlow()

    /** Search query for the drill-in item screen. */
    private val _itemSearchQuery = MutableStateFlow("")
    val itemSearchQuery: StateFlow<String> = _itemSearchQuery.asStateFlow()

    fun setListSearchQuery(q: String) { _listSearchQuery.value = q }
    fun setItemSearchQuery(q: String) { _itemSearchQuery.value = q }
    fun clearItemSearchQuery() { _itemSearchQuery.value = "" }

    /** Creates a new list entry in the lists table (idempotent — IGNORE conflict). */
    fun addList(name: String) {
        val trimmed = name.trim().lowercase()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            listNameDao.insert(ListNameEntity(name = trimmed))
        }
    }

    fun toggleChecked(item: ListItemEntity) {
        viewModelScope.launch {
            if (item.checked) dao.markUnchecked(item.id) else dao.markChecked(item.id)
        }
    }

    fun addItem(listName: String, itemText: String) {
        val trimmed = itemText.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            dao.insert(ListItemEntity(listName = listName, item = trimmed))
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch { dao.deleteItem(id) }
    }

    fun clearChecked(listName: String) {
        viewModelScope.launch { dao.deleteChecked(listName) }
    }

    fun deleteList(listName: String) {
        viewModelScope.launch {
            dao.deleteList(listName)
            listNameDao.deleteByName(listName)
        }
    }
}

