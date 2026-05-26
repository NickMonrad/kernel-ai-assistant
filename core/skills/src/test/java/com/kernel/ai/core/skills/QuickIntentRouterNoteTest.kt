package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tier 2 note-taking intent routing tests.
 *
 * Tests QuickIntentRouter regex patterns for create_note and list_notes intents,
 * and verifies list_notes is in FAST_PATH_INTENTS.
 *
 * Run with: ./gradlew :core:skills:testDebugUnitTest --tests "*.QuickIntentRouterNoteTest"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("QuickIntentRouter — Note-taking intents")
class QuickIntentRouterNoteTest {

    private val router = QuickIntentRouter()

    // ─── create_note patterns ───────────────────────────────────────────────

    @Nested
    @DisplayName("create_note")
    inner class CreateNoteTests {

        @ParameterizedTest(name = "[create_note] input: {0}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterNoteTest#createNoteInputs")
        fun `create_note regex matches`(input: String) {
            val result = router.route(input)
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertEquals("create_note", intentName, "Expected create_note for: $input")
        }

        @Test
        fun `create_note with write down extracts content`() {
            val result = router.route("write down buy milk")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("buy milk", intent?.params["content"])
        }

        @Test
        fun `create_note with jot down extracts content`() {
            val result = router.route("jot down remember password")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("remember password", intent?.params["content"])
        }

        @Test
        fun `create_note with put down extracts content`() {
            val result = router.route("put down shopping list")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("shopping list", intent?.params["content"])
        }
    }

    // ─── list_notes patterns ────────────────────────────────────────────────

    @Nested
    @DisplayName("list_notes")
    inner class ListNotesTests {

        @ParameterizedTest(name = "[list_notes] input: {0}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterNoteTest#listNotesInputs")
        fun `list_notes regex matches`(input: String) {
            val result = router.route(input)
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertEquals("list_notes", intentName, "Expected list_notes for: $input")
        }
    }

    // ─── FAST_PATH_INTENTS ──────────────────────────────────────────────────

    @Test
    fun `list_notes is in FAST_PATH_INTENTS`() {
        assertTrue(
            QuickIntentRouter.FAST_PATH_INTENTS.contains("list_notes"),
            "list_notes must be in FAST_PATH_INTENTS for instant execution",
        )
    }
        @Test
        fun `create_note with voice memo extracts content`() {
            val result = router.route("voice memo buy milk")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("buy milk", intent?.params["content"])
        }

        @Test
        fun `create_note with voice memo about extracts content`() {
            val result = router.route("voice memo about groceries")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("groceries", intent?.params["content"])
        }

        @Test
        fun `create_note with voice memo colon extracts content`() {
            val result = router.route("voice memo: meeting notes")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("meeting notes", intent?.params["content"])
        }
        @Test
        fun `create_note with voice memo dash extracts content`() {
            val result = router.route("voice memo - call the doctor")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("call the doctor", intent?.params["content"])
        }
        @Test
        fun `create_note with create a voice memo extracts content`() {
            val result = router.route("create a voice memo buy milk")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("buy milk", intent?.params["content"])
        }

        @Test
        fun `create_note with add a voice memo extracts content`() {
            val result = router.route("add a voice memo about groceries")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("groceries", intent?.params["content"])
        }

        @Test
        fun `create_note with create a voice memo colon extracts content`() {
            val result = router.route("create a voice memo: meeting notes")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("meeting notes", intent?.params["content"])
        }

        @Test
        fun `create_note with please add a voice memo extracts content`() {
            val result = router.route("please add a voice memo call the doctor")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("call the doctor", intent?.params["content"])
        }

    companion object {
        @JvmStatic
        fun createNoteInputs(): Stream<Arguments> = Stream.of(
            Arguments.of("write down buy milk"),
            Arguments.of("write down meeting notes"),
            Arguments.of("write down call mom"),
            Arguments.of("please write down meeting notes"),
            Arguments.of("can you write down groceries"),
            Arguments.of("jot down remember password"),
            Arguments.of("jot down call the doctor"),
            Arguments.of("put down shopping list"),
            Arguments.of("put down the meeting agenda"),
            Arguments.of("create a note about groceries"),
            Arguments.of("create a note about dinner plan"),
            Arguments.of("please create a note about groceries"),
            Arguments.of("can you create a note about dinner"),
            Arguments.of("create note: meeting notes"),
            Arguments.of("create note-reminders for tomorrow"),
            Arguments.of("take a note about meeting"),
            Arguments.of("voice memo buy milk"),
            Arguments.of("voice memo about groceries"),
            Arguments.of("voice memo: meeting notes"),
            Arguments.of("voice memo - call the doctor"),
            Arguments.of("create a voice memo buy milk"),
            Arguments.of("add a voice memo about groceries"),
            Arguments.of("create a voice memo: meeting notes"),
            Arguments.of("please add a voice memo call the doctor"),
            Arguments.of("can you create a voice memo about dinner"),
        )

        @JvmStatic
        fun listNotesInputs(): Stream<Arguments> = Stream.of(
            Arguments.of("show my notes"),
            Arguments.of("list my notes"),
            Arguments.of("display my notes"),
            Arguments.of("show me my notes"),
            Arguments.of("list for me my notes"),
            Arguments.of("what notes"),
            Arguments.of("what have I written down"),
            Arguments.of("what did I write down"),
            Arguments.of("my notes"),
        )
    }
}
