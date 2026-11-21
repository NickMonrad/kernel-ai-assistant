package com.kernel.ai.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechStopCardTest {
    private val posted = mutableListOf<String>()
    private var cancels = 0
    private var stops = 0
    private var foregroundStarts = 0
    private var foregroundAccepts = true

    private val card = SpeechStopCard<String>(
        startForeground = { _ ->
            foregroundStarts++
            foregroundAccepts
        },
        postCard = { token -> posted += token },
        cancelCard = { cancels++ },
        stopForeground = { stops++ },
    )

    @Test
    fun `promoted service is the only poster of the card`() {
        card.show("token")
        card.hide()

        assertEquals(1, foregroundStarts)
        assertEquals(emptyList<String>(), posted)
        assertEquals(0, cancels)
        assertEquals(1, stops)
    }

    @Test
    fun `denied foreground start posts the card directly and cancels it on hide`() {
        foregroundAccepts = false

        card.show("token")
        assertEquals(listOf("token"), posted)

        card.hide()
        assertEquals(1, cancels)
        assertEquals(1, stops)
    }

    @Test
    fun `hide after a denied start does not cancel again on a second hide`() {
        foregroundAccepts = false
        card.show("token")
        card.hide()

        card.hide()

        assertEquals(1, cancels)
    }

    @Test
    fun `a later promoted start does not leave a direct card for hide to cancel`() {
        foregroundAccepts = false
        card.show("first")
        card.hide()

        foregroundAccepts = true
        card.show("second")
        card.hide()

        assertEquals(listOf("first"), posted)
        assertEquals(1, cancels)
        assertEquals(2, stops)
    }
}
