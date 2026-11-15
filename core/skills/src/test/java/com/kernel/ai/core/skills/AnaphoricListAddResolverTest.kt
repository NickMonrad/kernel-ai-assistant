package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnaphoricListAddResolverTest {

    @Test
    fun `resolves a singular item stated in the same utterance`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(listOf("milk"), "shopping list"),
            AnaphoricListAddResolver.resolve(
                "The only item is milk. Add it to my shopping list.",
                previousUserMessage = null,
            ),
        )
    }

    @Test
    fun `resolves plural items stated in the same utterance`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(listOf("apples", "rice"), "grocery list"),
            AnaphoricListAddResolver.resolve(
                "The items are apples and rice. Add them to my grocery list.",
                previousUserMessage = null,
            ),
        )
    }

    @Test
    fun `resolves plural items from the previous user turn`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(listOf("oats", "tea"), "shopping list"),
            AnaphoricListAddResolver.resolve(
                "Add them to my shopping list.",
                previousUserMessage = "The items are oats and tea.",
            ),
        )
    }

    @Test
    fun `asks when realistic prior turns do not match the supported explicit declarations`() {
        val cases = listOf(
            "we're out of milk and eggs." to "Add them to my grocery list.",
            "I need to buy bread." to "Add it to my shopping list.",
        )

        cases.forEach { (previousTurn, request) ->
            assertTrue(
                AnaphoricListAddResolver.resolve(request, previousTurn) is AnaphoricListAddResolver.Result.Ask,
                previousTurn,
            )
        }
    }

    @Test
    fun `does not intercept anaphoric actions without an explicit list target`() {
        val requests = listOf(
            "add it to my calendar",
            "put that in my notes",
            "add this to the reminder",
            "add them to the meeting invite",
        )

        requests.forEach { request ->
            assertNull(AnaphoricListAddResolver.resolve(request, previousUserMessage = "The item is milk."))
        }
    }

    @Test
    fun `asks when favourite coffee has no explicit item referent`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Ask("Which item do you mean?"),
            AnaphoricListAddResolver.resolve(
                "What's my favourite coffee and add it to my shopping list.",
                previousUserMessage = null,
            ),
        )
    }

    @Test
    fun `asks instead of choosing between multiple singular referents`() {
        val result = AnaphoricListAddResolver.resolve(
            "Add it to my shopping list.",
            previousUserMessage = "The items are apples and rice.",
        )

        assertTrue(result is AnaphoricListAddResolver.Result.Ask)
        assertTrue((result as AnaphoricListAddResolver.Result.Ask).question.endsWith("?"))
    }

    @Test
    fun `asks when the prior turn does not identify a concrete referent`() {
        val result = AnaphoricListAddResolver.resolve(
            "What's my favourite coffee and add it to my shopping list.",
            previousUserMessage = null,
        )

        assertTrue(result is AnaphoricListAddResolver.Result.Ask)
        assertTrue((result as AnaphoricListAddResolver.Result.Ask).question.endsWith("?"))
    }

    @Test
    fun `asks rather than assuming a list from an unqualified list mention`() {
        listOf("my list", "the list", "a list").forEach { target ->
            val result = AnaphoricListAddResolver.resolve(
                "Add them to $target.",
                previousUserMessage = "The items are oats and tea.",
            )

            assertTrue(result is AnaphoricListAddResolver.Result.Ask)
        }
    }

    @Test
    fun `ignores requests without an anaphoric list addition`() {
        assertNull(
            AnaphoricListAddResolver.resolve(
                "Add coffee to my shopping list.",
                previousUserMessage = "The items are oats and tea.",
            ),
        )
    }
}
