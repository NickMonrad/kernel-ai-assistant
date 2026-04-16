package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val dao: ListItemDao,
) : ViewModel() {

    /** All items grouped by list name, ordered for display. */
    val groupedItems: StateFlow<Map<String, List<ListItemEntity>>> =
        dao.observeAll()
            .map { items -> items.groupBy { it.listName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun toggleChecked(item: ListItemEntity) {
        viewModelScope.launch {
            if (item.checked) dao.markUnchecked(item.id) else dao.markChecked(item.id)
        }
    }

    fun clearChecked(listName: String) {
        viewModelScope.launch { dao.deleteChecked(listName) }
    }

    fun deleteList(listName: String) {
        viewModelScope.launch { dao.deleteList(listName) }
    }
}
