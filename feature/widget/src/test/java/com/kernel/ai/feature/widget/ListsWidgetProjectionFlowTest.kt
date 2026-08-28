package com.kernel.ai.feature.widget

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListsWidgetProjectionFlowTest {

    @Test
    fun `selection and Room changes update the active projection`() = runTest {
        val selectedListId = MutableStateFlow(ListsWidgetConfig.INVALID)
        val listOne = ListNameEntity(id = 1L, name = "shopping list", createdAt = 0L, updatedAt = 0L)
        val listTwo = ListNameEntity(id = 2L, name = "work", createdAt = 0L, updatedAt = 0L)
        val metadata = MutableStateFlow(listOf(listOne, listTwo))
        val listOneItems = MutableStateFlow(listOf(item(1L, 1L, "jam")))
        val listTwoItems = MutableStateFlow(listOf(item(2L, 2L, "email")))
        val itemFlows = mapOf(1L to listOneItems, 2L to listTwoItems)
        val emissions = mutableListOf<ListsWidgetProjection>()
        val job = launch {
            observeListsWidgetProjection(
                selectedListIds = selectedListId,
                listMetadata = metadata,
                listItems = { itemFlows.getValue(it) },
            ).collect { emissions += it }
        }

        runCurrent()
        assertTrue(emissions.last() is ListsWidgetProjection.NotConfigured)

        selectedListId.value = 1L
        runCurrent()
        assertEquals(ListsWidgetProjection.Configured(1L, "shopping list", listOf("jam")), emissions.last())

        metadata.value = listOf(listOne.copy(name = "renamed"), listTwo)
        runCurrent()
        assertEquals(ListsWidgetProjection.Configured(1L, "renamed", listOf("jam")), emissions.last())

        listOneItems.value = listOf(item(1L, 1L, "jam"), item(3L, 1L, "eggs"))
        runCurrent()
        assertEquals(
            ListsWidgetProjection.Configured(1L, "renamed", listOf("jam", "eggs")),
            emissions.last(),
        )

        metadata.value = listOf(listOne.copy(name = "renamed", archivedAt = 1L), listTwo)
        runCurrent()
        assertEquals(
            ListsWidgetProjection.Archived(1L, "renamed", listOf("jam", "eggs")),
            emissions.last(),
        )

        metadata.value = listOf(listTwo)
        runCurrent()
        assertTrue(emissions.last() is ListsWidgetProjection.Missing)

        selectedListId.value = 2L
        runCurrent()
        assertEquals(ListsWidgetProjection.Configured(2L, "work", listOf("email")), emissions.last())

        val emissionsAfterReconfigure = emissions.size
        listOneItems.value = listOf(item(4L, 1L, "stale old-list item"))
        runCurrent()
        assertEquals(emissionsAfterReconfigure, emissions.size)

        job.cancel()
    }

    private fun item(id: Long, listId: Long, text: String) = ListItemEntity(
        id = id,
        listId = listId,
        text = text,
        createdAt = id,
        updatedAt = id,
        displayOrder = id,
    )
}
