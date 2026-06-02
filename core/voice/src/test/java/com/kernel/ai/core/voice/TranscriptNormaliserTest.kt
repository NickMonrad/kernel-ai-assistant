package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [TranscriptNormaliser].
 *
 * Covers acceptance criteria from issues #935, #939, and #1017,
 * plus idempotence and false-negative (no-op) cases.
 */
class TranscriptNormaliserTest {

    // ── #939 — wharepaku ──────────────────────────────────────────────────────

    @Test
    fun `fattybaku becomes wharepaku`() {
        assertEquals(
            "where is the wharepaku",
            TranscriptNormaliser.normalise("where is the fattybaku"),
        )
    }

    @Test
    fun `farah paco becomes wharepaku`() {
        assertEquals(
            "where is the wharepaku",
            TranscriptNormaliser.normalise("where is the farah paco"),
        )
    }

    @Test
    fun `wharepaku stays wharepaku (idempotent)`() {
        assertEquals(
            "where is the wharepaku",
            TranscriptNormaliser.normalise("where is the wharepaku"),
        )
    }

    // ── #935 — taniwha / kumara / chocka ──────────────────────────────────────

    @Test
    fun `tonifa becomes taniwha`() {
        assertEquals(
            "tell me about taniwha",
            TranscriptNormaliser.normalise("tell me about tonifa"),
        )
    }

    @Test
    fun `tanifa becomes taniwha`() {
        assertEquals(
            "tell me about taniwha",
            TranscriptNormaliser.normalise("tell me about tanifa"),
        )
    }

    @Test
    fun `comrade becomes kumara`() {
        assertEquals(
            "add kumara to my shopping list",
            TranscriptNormaliser.normalise("add comrade to my shopping list"),
        )
    }

    @Test
    fun `chaka becomes chocka`() {
        assertEquals(
            "a chocka block",
            TranscriptNormaliser.normalise("a chaka block"),
        )
    }

    // ── #1017 — Mills / mls ───────────────────────────────────────────────────

    @Test
    fun `300 mls becomes 300 ml in context`() {
        assertEquals(
            "add 250 g of butter and 300 ml of milk to my shopping list",
            TranscriptNormaliser.normalise(
                "add 250 g of butter and 300 mls of milk to my shopping list",
            ),
        )
    }

    @Test
    fun `300 Mills becomes 300 ml`() {
        assertEquals(
            "add 300 ml of milk to my list",
            TranscriptNormaliser.normalise("add 300 Mills of milk to my list"),
        )
    }

    @Test
    fun `200 mils becomes 200 ml`() {
        assertEquals(
            "add 200 ml of cream",
            TranscriptNormaliser.normalise("add 200 mils of cream"),
        )
    }

    @Test
    fun `100 ml's becomes 100 ml`() {
        assertEquals(
            "add 100 ml of oil",
            TranscriptNormaliser.normalise("add 100 ml's of oil"),
        )
    }

    // ── No false positives ────────────────────────────────────────────────────

    @Test
    fun `hello world unchanged`() {
        assertEquals("hello world", TranscriptNormaliser.normalise("hello world"))
    }

    @Test
    fun `milkman unchanged`() {
        assertEquals(
            "the milkman delivered milk",
            TranscriptNormaliser.normalise("the milkman delivered milk"),
        )
    }

    @Test
    fun `lost unchanged by central normaliser`() {
        assertEquals("I lost my keys", TranscriptNormaliser.normalise("I lost my keys"))
    }

    @Test
    fun `timer unchanged`() {
        assertEquals(
            "can you set a timer for 5 minutes",
            TranscriptNormaliser.normalise("can you set a timer for 5 minutes"),
        )
    }

    // ── Known false positive ──────────────────────────────────────────────────

    @Test
    fun `comrade in arms becomes kumara in arms (documented limitation)`() {
        assertEquals(
            "i was a kumara in arms",
            TranscriptNormaliser.normalise("i was a comrade in arms"),
        )
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `blank input returns blank`() {
        assertEquals("", TranscriptNormaliser.normalise(""))
        assertEquals("   ", TranscriptNormaliser.normalise("   "))  // isBlank returns original
    }

    @Test
    fun `idempotent normalise is idempotent`() {
        val inputs = listOf(
            "where is the fattybaku",
            "add comrade to my shopping list",
            "300 mls of milk",
            "hello world",
            "I lost my keys",
        )
        for (input in inputs) {
            val once = TranscriptNormaliser.normalise(input)
            val twice = TranscriptNormaliser.normalise(once)
            assertEquals(once, twice, "Idempotence failed for input: $input")
        }
    }
}
