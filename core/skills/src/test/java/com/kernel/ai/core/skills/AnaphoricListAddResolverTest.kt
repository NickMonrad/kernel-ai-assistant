package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnaphoricListAddResolverTest {

    @Test
    fun `resolves singular item stated in the same utterance`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(listOf("flat white"), "shopping list"),
            AnaphoricListAddResolver.resolve(
                "My favourite coffee is flat white, and add it to my shopping list.",
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
    fun `resolves a favourite coffee explicitly named in the previous user turn`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(
                listOf("flat white"),
                "shopping list",
                answerPrefix = "Your favourite coffee is flat white.",
            ),
            AnaphoricListAddResolver.resolve(
                "What's my favourite coffee and add it to my shopping list.",
                previousUserMessage = "My favourite coffee is flat white.",
            ),
        )
    }

    @Test
    fun `resolves a favourite coffee from retrieved memory evidence`() {
        assertEquals(
            AnaphoricListAddResolver.Result.Add(
                listOf("flat white"),
                "shopping list",
                answerPrefix = "Your favourite coffee is flat white.",
            ),
            AnaphoricListAddResolver.resolve(
                "What's my favourite coffee and add it to my shopping list.",
                previousUserMessage = null,
                retrievedMemory = "Relevant memory evidence: my favourite coffee is a flat white.",
            ),
        )
    }

    @Test
    fun `asks when retrieved memory contains conflicting favourite coffees`() {
        val result = AnaphoricListAddResolver.resolve(
            "What's my favourite coffee and add it to my shopping list.",
            previousUserMessage = null,
            retrievedMemory = "my favourite coffee is a flat white\nmy favourite coffee is a long black",
        )

        assertTrue(result is AnaphoricListAddResolver.Result.Ask)
    }

    @Test
    fun `requests memory lookup only for an unanswered favourite coffee reference`() {
        assertTrue(
            AnaphoricListAddResolver.needsMemoryLookup(
                "What's my favourite coffee and add it to my shopping list.",
                previousUserMessage = null,
            ),
        )
        assertFalse(
            AnaphoricListAddResolver.needsMemoryLookup(
                "What's my favourite coffee and add it to my shopping list.",
                previousUserMessage = "My favourite coffee is flat white.",
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
