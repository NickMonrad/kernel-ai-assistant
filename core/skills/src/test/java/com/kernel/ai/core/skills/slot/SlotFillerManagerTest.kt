package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SlotFillerManagerTest {
    private lateinit var manager: SlotFillerManager

    private val conversationOne = "conv-1"
    private val conversationTwo = "conv-2"

    @BeforeEach
    fun setUp() {
        manager = SlotFillerManager(QuickIntentRouter())
    }

    @Test
    fun `send email chains contact subject and body before completion`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "send_email",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "contact",
                    promptTemplate = "Who would you like to email?",
                ),
            ),
        )

        val first = assertInstanceOf(
            SlotFillResult.NeedsMore::class.java,
            manager.onUserReply(conversationOne, "to Nick"),
        )
        assertTrue(manager.hasPendingFor(conversationOne))
        assertEquals("subject", first.request.missingSlot.name)
        assertEquals(
            "What's the subject of your email to Nick?",
            first.request.promptMessage,
        )

        val second = assertInstanceOf(
            SlotFillResult.NeedsMore::class.java,
            manager.onUserReply(conversationOne, "Weekend plans"),
        )
        assertTrue(manager.hasPendingFor(conversationOne))
        assertEquals("body", second.request.missingSlot.name)
        assertEquals(
            "What would you like the email to say?",
            second.request.promptMessage,
        )

        val completed = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "Let's catch up on Saturday"),
        )
        assertFalse(manager.hasPending)
        assertEquals(
            mapOf(
                "contact" to "Nick",
                "subject" to "Weekend plans",
                "body" to "Let's catch up on Saturday",
            ),
            completed.params,
        )
    }

    @Test
    fun `pending slot fill is scoped to its originating conversation`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "send_email",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "contact",
                    promptTemplate = "Who would you like to email?",
                ),
            ),
        )

        assertTrue(manager.hasPendingFor(conversationOne))
        assertFalse(manager.hasPendingFor(conversationTwo))
        assertNull(manager.pendingRequestFor(conversationTwo))

        val wrongConversationResult = manager.onUserReply(conversationTwo, "Nick")

        assertInstanceOf(SlotFillResult.Cancelled::class.java, wrongConversationResult)
        assertTrue(manager.hasPendingFor(conversationOne))
        assertEquals("contact", manager.pendingRequestFor(conversationOne)?.missingSlot?.name)
    }

    @Test
    fun `starting a second conversation slot fill preserves the first one`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "send_email",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "contact",
                    promptTemplate = "Who would you like to email?",
                ),
            ),
        )
        manager.startSlotFill(
            conversationTwo,
            PendingSlotRequest(
                intentName = "add_to_list",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "item",
                    promptTemplate = "What would you like to add?",
                ),
            ),
        )

        assertTrue(manager.hasPendingFor(conversationOne))
        assertTrue(manager.hasPendingFor(conversationTwo))
        assertEquals("contact", manager.pendingRequestFor(conversationOne)?.missingSlot?.name)
        assertEquals("item", manager.pendingRequestFor(conversationTwo)?.missingSlot?.name)
    }

    @Test
    fun `add to list asks for list name after item reply`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "add_to_list",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "item",
                    promptTemplate = "What would you like to add?",
                ),
            ),
        )

        val next = assertInstanceOf(
            SlotFillResult.NeedsMore::class.java,
            manager.onUserReply(conversationOne, "milk"),
        )
        assertTrue(manager.hasPendingFor(conversationOne))
        assertEquals("list_name", next.request.missingSlot.name)
        assertEquals("Which list should I add it to?", next.request.promptMessage)

        val completed = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "on my shopping list"),
        )
        assertFalse(manager.hasPending)
        assertEquals(
            mapOf(
                "item" to "milk",
                "list_name" to "shopping list",
            ),
            completed.params,
        )
    }

    @Test
    fun `create list preserves leading words in legitimate list names`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "create_list",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "list_name",
                    promptTemplate = "What would you like to call the list?",
                ),
            ),
        )

        val titled = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "The Boys"),
        )
        assertEquals("The Boys", titled.params["list_name"])

        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "create_list",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "list_name",
                    promptTemplate = "What would you like to call the list?",
                ),
            ),
        )

        val todo = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "to do list"),
        )
        assertEquals("to-do list", todo.params["list_name"])
    }

    @Test
    fun `generic shopping list replies normalize without mangling named lists`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "add_to_list",
                existingParams = mapOf("item" to "milk"),
                missingSlot = SlotSpec(
                    name = "list_name",
                    promptTemplate = "Which list should I add it to?",
                ),
            ),
        )

        val possessive = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "my shopping list"),
        )
        assertEquals("shopping list", possessive.params["list_name"])

        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "add_to_list",
                existingParams = mapOf("item" to "milk"),
                missingSlot = SlotSpec(
                    name = "list_name",
                    promptTemplate = "Which list should I add it to?",
                ),
            ),
        )

        val article = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "the shopping list"),
        )
        assertEquals("shopping list", article.params["list_name"])

        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "create_list",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "list_name",
                    promptTemplate = "What would you like to call the list?",
                ),
            ),
        )

        val named = assertInstanceOf(
            SlotFillResult.Completed::class.java,
            manager.onUserReply(conversationOne, "My Tasks"),
        )
        assertEquals("My Tasks", named.params["list_name"])
    }

    @Test
    fun `blank reply cancels and clears pending request`() {
        manager.startSlotFill(
            conversationOne,
            PendingSlotRequest(
                intentName = "send_sms",
                existingParams = emptyMap(),
                missingSlot = SlotSpec(
                    name = "contact",
                    promptTemplate = "Who do you want to send a message to?",
                ),
            ),
        )

        val result = manager.onUserReply(conversationOne, "   ")

        assertInstanceOf(SlotFillResult.Cancelled::class.java, result)
        assertFalse(manager.hasPending)
        assertNull(manager.pendingRequest)
    }
}