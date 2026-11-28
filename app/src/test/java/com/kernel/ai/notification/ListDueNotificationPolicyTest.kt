package com.kernel.ai.notification

import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.navigation.isAllowedExternalNavigationRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListDueNotificationPolicyTest {

    private fun item(checked: Boolean) = ListItemEntity(
        id = 8L,
        listId = 42L,
        text = "call mum",
        createdAt = 0L,
        updatedAt = 0L,
        checked = checked,
    )

    @Test
    fun `reminder for an item deleted before it fires is not shown`() {
        assertFalse(shouldShowListReminder(null))
    }

    @Test
    fun `reminder for an item ticked off before it fires is not shown`() {
        assertFalse(shouldShowListReminder(item(checked = true)))
    }

    @Test
    fun `reminder for an open item is shown`() {
        assertTrue(shouldShowListReminder(item(checked = false)))
    }

    @Test
    fun `tapping a reminder opens its list through a route the nav host accepts`() {
        val route = listReminderRoute(42L)

        assertEquals("lists/42", route)
        assertTrue(isAllowedExternalNavigationRoute(route))
    }
}
