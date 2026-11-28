package com.kernel.ai.notification

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.navigation.isAllowedExternalNavigationRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListDueNotificationPolicyTest {

    private fun item(
        checked: Boolean = false,
        lifecycle: String = "ACTIVE",
    ) = ListItemEntity(
        id = 8L,
        listId = 42L,
        text = "call mum",
        createdAt = 0L,
        updatedAt = 0L,
        checked = checked,
        lifecycle = lifecycle,
    )

    private fun list(
        lifecycle: String = "ACTIVE",
        archivedAt: Long? = null,
    ) = ListNameEntity(
        id = 42L,
        name = "shopping list",
        lifecycle = lifecycle,
        archivedAt = archivedAt,
    )

    @Test
    fun `reminder for an item that was never stored is not shown`() {
        assertNull(dueReminderFor(null, list()))
    }

    @Test
    fun `reminder for an item deleted before it fires is not shown even though its row remains`() {
        assertNull(dueReminderFor(item(lifecycle = "DELETED"), list()))
    }

    @Test
    fun `reminder for an item ticked off before it fires is not shown`() {
        assertNull(dueReminderFor(item(checked = true), list()))
    }

    @Test
    fun `reminder whose list was deleted is not shown`() {
        assertNull(dueReminderFor(item(), list(lifecycle = "DELETED")))
    }

    @Test
    fun `reminder whose list was archived is not shown`() {
        assertNull(dueReminderFor(item(), list(archivedAt = 1_000L)))
    }

    @Test
    fun `open item in an active list is shown with the list's current name from the database`() {
        val reminder = dueReminderFor(item(), list())

        assertEquals(DueReminder(itemId = 8L, itemText = "call mum", listId = 42L, listName = "shopping list"), reminder)
    }

    @Test
    fun `tapping a reminder opens its list through a route the nav host accepts`() {
        val route = listReminderRoute(42L)

        assertEquals("lists/42", route)
        assertTrue(isAllowedExternalNavigationRoute(route))
    }
}
