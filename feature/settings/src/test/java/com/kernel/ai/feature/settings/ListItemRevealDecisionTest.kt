package com.kernel.ai.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListItemRevealDecisionTest {
    @Test
    fun `waits until mutation identity appears in the source projection`() {
        assertEquals(
            AddedItemRevealDecision.AwaitingProjection,
            decideAddedItemReveal(
                sourceItemExists = false,
                displayedActiveIndex = -1,
                renderedActiveIndex = -1,
                itemIsVisible = false,
                currentLayoutItemCount = 1,
                expectedLayoutItemCount = 2,
            ),
        )
    }

    @Test
    fun `confirms an item hidden by the current search or filter`() {
        assertEquals(
            AddedItemRevealDecision.HiddenBySearchOrFilter,
            decideAddedItemReveal(
                sourceItemExists = true,
                displayedActiveIndex = -1,
                renderedActiveIndex = -1,
                itemIsVisible = false,
                currentLayoutItemCount = 1,
                expectedLayoutItemCount = 1,
            ),
        )
    }

    @Test
    fun `waits until the active list renders the projected position`() {
        assertEquals(
            AddedItemRevealDecision.AwaitingProjection,
            decideAddedItemReveal(
                sourceItemExists = true,
                displayedActiveIndex = 2,
                renderedActiveIndex = -1,
                itemIsVisible = false,
                currentLayoutItemCount = 4,
                expectedLayoutItemCount = 5,
            ),
        )
    }

    @Test
    fun `waits until lazy list layout reflects the current row count`() {
        assertEquals(
            AddedItemRevealDecision.AwaitingProjection,
            decideAddedItemReveal(
                sourceItemExists = true,
                displayedActiveIndex = 2,
                renderedActiveIndex = 2,
                itemIsVisible = false,
                currentLayoutItemCount = 4,
                expectedLayoutItemCount = 5,
            ),
        )
    }

    @Test
    fun `does not scroll when the stable row key is already visible`() {
        assertEquals(
            AddedItemRevealDecision.AlreadyVisible,
            decideAddedItemReveal(
                sourceItemExists = true,
                displayedActiveIndex = 2,
                renderedActiveIndex = 2,
                itemIsVisible = true,
                currentLayoutItemCount = 5,
                expectedLayoutItemCount = 5,
            ),
        )
    }

    @Test
    fun `scrolls to the actual projected index rather than an assumed edge`() {
        assertEquals(
            AddedItemRevealDecision.ScrollToIndex(index = 2),
            decideAddedItemReveal(
                sourceItemExists = true,
                displayedActiveIndex = 2,
                renderedActiveIndex = 2,
                itemIsVisible = false,
                currentLayoutItemCount = 5,
                expectedLayoutItemCount = 5,
            ),
        )
    }
}
