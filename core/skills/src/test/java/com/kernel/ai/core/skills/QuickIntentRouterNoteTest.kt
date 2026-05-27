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

        @Test
        fun `bare 'add a voice memo' returns NeedsSlot for create_note`() {
            val result = router.route("Add a voice memo")
            assertTrue(result is QuickIntentRouter.RouteResult.NeedsSlot,
                "Expected NeedsSlot but got: $result")
            val slot = (result as QuickIntentRouter.RouteResult.NeedsSlot)
            assertEquals("create_note", slot.intent.intentName)
            assertEquals("content", slot.missingSlot.name)
        }

        @Test
        fun `bare 'voice memo' returns NeedsSlot for create_note`() {
            val result = router.route("voice memo")
            assertTrue(result is QuickIntentRouter.RouteResult.NeedsSlot,
                "Expected NeedsSlot but got: $result")
            assertEquals("create_note", (result as QuickIntentRouter.RouteResult.NeedsSlot).intent.intentName)
        }

        @Test
        fun `bare 'make a note' returns NeedsSlot for create_note, not save_memory`() {
            val result = router.route("make a note")
            assertTrue(result is QuickIntentRouter.RouteResult.NeedsSlot,
                "Expected NeedsSlot but got: $result")
            val slot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("create_note", slot.intent.intentName,
                "bare 'make a note' must route to create_note, not save_memory")
        }

        @Test
        fun `bare 'take a note' returns NeedsSlot for create_note`() {
            val result = router.route("take a note")
            assertTrue(result is QuickIntentRouter.RouteResult.NeedsSlot,
                "Expected NeedsSlot but got: $result")
            assertEquals("create_note", (result as QuickIntentRouter.RouteResult.NeedsSlot).intent.intentName)
        }

        @Test
        fun `'create a note for X' strips 'for' from content`() {
            val result = router.route("create a note for the dentist")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("the dentist", intent?.params["content"],
                "'for' preposition should be stripped from content")
        }

        @Test
        fun `'add a note about X' routes to create_note`() {
            val result = router.route("add a note about groceries")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
        }

        @Test
        fun `'what notes do I have' routes to list_notes`() {
            val result = router.route("what notes do I have")
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertEquals("list_notes", intentName)
        }

        @Test
        fun `'check my notes' routes to list_notes`() {
            val result = router.route("check my notes")
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertEquals("list_notes", intentName)
        }
    // ─── Negative tests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("negative routing")
    inner class NegativeTests {

        @Test
        fun `make a note that X routes to create_note not save_memory`() {
            val result = router.route("make a note that buy milk")
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertEquals("create_note", intentName, "make a note that X should route to create_note")
        }

        @Test
        fun `take a note about X strips the about preposition from content`() {
            val result = router.route("take a note about meeting")
            val intent = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent
                else -> null
            }
            assertEquals("create_note", intent?.intentName)
            assertEquals("meeting", intent?.params["content"],
                "Leading preposition 'about' must be stripped from content")
        }

        @Test
        fun `list_notes does not match "show my notes app"`() {
            val result = router.route("show my notes app")
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertNotEquals("list_notes", intentName,
                "show my notes app should not match list_notes")
        }

        @Test
        fun `list_notes does not match "what notes app should I use"`() {
            val result = router.route("what notes app should I use")
            val intentName = when (result) {
                is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
                is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
                else -> null
            }
            assertNotEquals("list_notes", intentName,
                "what notes app should I use should not match list_notes")
        }
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
            // Bare voice memo phrases — no content → NeedsSlot (still routes to create_note)
            Arguments.of("voice memo"),
            Arguments.of("add a voice memo"),
            Arguments.of("create a voice memo"),
            Arguments.of("record a voice memo"),
            Arguments.of("make a note that I'm vegetarian"),
            // Bare make/take a note — NeedsSlot → create_note (not save_memory)
            Arguments.of("make a note"),
            Arguments.of("take a note"),
            Arguments.of("please make a note"),
            Arguments.of("can you take a note"),
            // add a note / new note phrasings
            Arguments.of("add a note about groceries"),
            Arguments.of("add a note for the dentist"),
            Arguments.of("add a note: call mom"),
            Arguments.of("start a note: reminders"),
            Arguments.of("new note: meeting at 3pm"),
            // create a note for X — "for" preposition must be stripped from content
            Arguments.of("create a note for the dentist"),
            Arguments.of("create a note for later"),
            // note: X shorthand
            Arguments.of("note: call dentist"),
            Arguments.of("note: buy milk"),
            // bare create/add → NeedsSlot
            Arguments.of("create a note"),
            Arguments.of("add a note"),
            // bare write/jot/put down → NeedsSlot
            Arguments.of("write something down"),
            Arguments.of("jot something down"),
            Arguments.of("put something down"),
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
            // New phrasings
            Arguments.of("check my notes"),
            Arguments.of("read my notes"),
            Arguments.of("read me my notes"),
            Arguments.of("pull up my notes"),
            Arguments.of("what notes do I have"),
            Arguments.of("what have I jotted down"),
            Arguments.of("what did I jot down"),
        )
    }
}
