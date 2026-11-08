package com.kernel.ai.feature.widget

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Pure, framework-free projection of list data into the widget's display states.
 *
 * Extracted from the Glance rendering path so the presentation logic is unit-testable
 * without Android/Glance runtime classes.
 */
sealed interface ListsWidgetProjection {
    /** The configured list id, or [ListsWidgetConfig.INVALID] when there is no selection. */
    val listId: Long

    /** A normal, configured, non-archived list with zero or more active items. */
    data class Configured(
        override val listId: Long,
        val listName: String,
        val activeItems: List<String>,
    ) : ListsWidgetProjection

    /** A configured list that has been archived (shown read-only). */
    data class Archived(
        override val listId: Long,
        val listName: String,
        val activeItems: List<String>,
    ) : ListsWidgetProjection

    /** A configured, non-archived list with no active items. */
    data class Empty(
        override val listId: Long,
        val listName: String,
    ) : ListsWidgetProjection

    /** The configured list no longer exists (deleted). */
    data object Missing : ListsWidgetProjection {
        override val listId: Long get() = ListsWidgetConfig.INVALID
    }

    /** No list has been selected for this widget instance yet. */
    data object NotConfigured : ListsWidgetProjection {
        override val listId: Long get() = ListsWidgetConfig.INVALID
    }
}

/**
 * Build the widget projection from raw list data.
 *
 * @param selectedListId the list id persisted for this widget instance.
 * @param name the resolved [ListNameEntity], or null if the list was deleted.
 * @param items all items for the list (checked + unchecked).
 */
fun projectListsWidget(
    selectedListId: Long,
    name: ListNameEntity?,
    items: List<ListItemEntity>,
): ListsWidgetProjection {
    if (selectedListId <= 0L) return ListsWidgetProjection.NotConfigured
    if (name == null) return ListsWidgetProjection.Missing

    val activeItems = items
        .filter { !it.checked }
        .sortedWith(compareBy({ it.displayOrder }, { it.createdAt }, { it.id }))
        .map { it.text }

    return if (name.archivedAt != null) {
        ListsWidgetProjection.Archived(selectedListId, name.name, activeItems)
    } else if (activeItems.isEmpty()) {
        ListsWidgetProjection.Empty(selectedListId, name.name)
    } else {
        ListsWidgetProjection.Configured(selectedListId, name.name, activeItems)
    }
}

/**
 * Observe one widget's selected list and project live metadata/items into widget content.
 *
 * The selected-list flow is per-widget, while the Room metadata flow covers all lists and is
 * filtered to the current selection. [flatMapLatest] drops the old item observation after
 * reconfiguration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeListsWidgetProjection(
    selectedListIds: Flow<Long>,
    listMetadata: Flow<List<ListNameEntity>>,
    listItems: (Long) -> Flow<List<ListItemEntity>>,
): Flow<ListsWidgetProjection> =
    selectedListIds.flatMapLatest { selectedListId ->
        if (selectedListId <= 0L) {
            flowOf(ListsWidgetProjection.NotConfigured)
        } else {
            combine(
                listMetadata.map { lists -> lists.firstOrNull { it.id == selectedListId } },
                listItems(selectedListId),
            ) { name, items ->
                projectListsWidget(selectedListId, name, items)
            }
        }
    }
