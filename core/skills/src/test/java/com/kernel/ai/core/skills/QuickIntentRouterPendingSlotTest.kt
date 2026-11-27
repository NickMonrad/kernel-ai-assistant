package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * #2082: an in-place match must ask a deterministic slot question (and so open SlotReply) only when a
 * required slot is missing or a present value fails its validator.
 */
class QuickIntentRouterPendingSlotTest {

    private val router = QuickIntentRouter()

    @Test
    fun `add_reminder with no params asks for the first missing required slot`() {
        val pending = router.pendingSlotFor("add_reminder", emptyMap())

        assertEquals("item", pending?.missingSlot?.name)
        assertEquals(emptyMap<String, String>(), pending?.existingParams)
    }

    @Test
    fun `add_reminder with every required slot present does not ask`() {
        val params = mapOf("item" to "call mum", "day" to "Monday", "time" to "17:00")

        assertNull(router.pendingSlotFor("add_reminder", params))
    }

    @Test
    fun `add_reminder with item and time does not ask for a day`() {
        val params = mapOf("item" to "put the bins out", "time" to "17:00")

        assertNull(router.pendingSlotFor("add_reminder", params))
    }
}
