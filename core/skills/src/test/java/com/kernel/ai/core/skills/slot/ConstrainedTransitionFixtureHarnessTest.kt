package com.kernel.ai.core.skills.slot

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Host oracle for the accepted reducer seam; proposals never execute a real tool.
 *
 * Enum-constrained contract (#1682 retry): the per-turn schema is built from app-owned held
 * actions. The model chooses a transition and one of the held action ids (or null); it writes no
 * free-text subject. The app resolves the canonical subject from its own state by id. Only slot
 * values the user supplies remain typed text fields.
 */
class ConstrainedTransitionFixtureHarnessTest {
    private enum class Transition { CONTINUE, CANCEL, REPLACE, NEW_TOPIC, CLARIFY }

    /** App-owned state; [subject] is canonical and never shown to the model as an echoable field. */
    private data class HeldAction(
        val id: String,
        val description: String,
        val subject: String,
        val pending: Boolean,
        val slots: List<String> = emptyList(),
    )

    private data class Proposal(
        val transition: Transition,
        val actionId: String?,
        val slotName: String?,
        val slotValue: String?,
    )

    private data class Expected(
        val transition: Transition,
        val actionId: String?,
        val slotName: String? = null,
        val slotValue: String? = null,
        val dispatchTarget: String? = null,
    )

    private data class Fixture(
        val name: String,
        val held: List<HeldAction>,
        val raw: String,
        val expected: Expected,
        val structuralExpected: Boolean = true,
        val semanticExpected: Boolean = true,
        val slotExpected: Boolean = true,
        val userReply: String? = null,
    ) {
        val transitionExpected: Transition
            get() = if (structuralExpected && semanticExpected && slotExpected) expected.transition else Transition.CLARIFY
        val dispatchExpected: String?
            get() = if (transitionExpected == Transition.CONTINUE) expected.dispatchTarget else null
    }

    private data class Result(
        val structuralValid: Boolean,
        val semanticCorrect: Boolean,
        val slotCorrect: Boolean,
        val transition: Transition,
        val dispatchTarget: String?,
        val resolvedSubject: String?,
    )

    private val wikiCheck = HeldAction(
        id = "wiki-check-1",
        description = "Wikipedia fact-check of the claim that prisoners brew alcohol in toilets",
        subject = "prisoners brewing alcohol in toilets",
        pending = true,
    )
    private val sydneyWeather = HeldAction(
        id = "weather-1",
        description = "weather lookup for Sydney, today",
        subject = "weather in Sydney",
        pending = false,
        slots = listOf("date"),
    )
    private val reminder = HeldAction(
        id = "reminder-1",
        description = "reminder to call the blinds people on Monday; waiting for the time",
        subject = "call the blinds people on Monday",
        pending = true,
        slots = listOf("time"),
    )
    private val timer42 = HeldAction("timer-42", "timer for 20 minutes, running", "20 minute timer", pending = false, slots = listOf("duration_minutes"))
    private val timer43 = HeldAction("timer-43", "timer for 25 minutes, running", "25 minute timer", pending = false, slots = listOf("duration_minutes"))

    @Test
    fun `per-turn schema offers only held ids and slot names and no free-text subject`() {
        val held = listOf(reminder, timer42)
        val schema = JsonParser.parseString(schemaFor(held)).asJsonObject
        val properties = schema.getAsJsonObject("properties")

        assertEquals(setOf("transition", "action_id", "slot_name", "slot_value"), properties.keySet())
        assertEquals(properties.keySet(), schema.getAsJsonArray("required").map { it.asString }.toSet())
        assertEquals(
            listOf("CONTINUE", "CANCEL", "REPLACE", "NEW_TOPIC", "CLARIFY"),
            properties.getAsJsonObject("transition").getAsJsonArray("enum").map { it.asString },
        )
        assertEquals(listOf("reminder-1", "timer-42", null), enumValues(properties, "action_id"))
        assertEquals(listOf("time", "duration_minutes", null), enumValues(properties, "slot_name"))
        val schemaText = schema.toString()
        held.forEach { action ->
            assertFalse(schemaText.contains(action.description), "display description is not an echoable value")
            assertFalse(schemaText.contains(action.subject), "canonical subject stays app-owned")
        }
        val rendered = renderHeldActions(held)
        assertFalse(rendered.contains("Pending reminder:"), "no display label resembling a field value")
        assertTrue(rendered.contains("- id reminder-1 [pending]: ${reminder.description}"))
    }

    @Test
    fun `host fixtures separate structural validity semantic correctness and dispatch safety`() {
        val fixtures = listOf(
            fixture("wikipedia disputed claim continues", listOf(wikiCheck, sydneyWeather), "CONTINUE", "wiki-check-1", dispatch = "wiki-check-1"),
            fixture("cancel pending reminder", listOf(reminder, timer42), "CANCEL", "reminder-1"),
            fixture("unrelated weather replaces pending reminder", listOf(reminder), "REPLACE", "reminder-1"),
            fixture(
                "Sydney weather tomorrow keeps location",
                listOf(sydneyWeather),
                "CONTINUE",
                "weather-1",
                slotName = "date",
                slotValue = "tomorrow",
                dispatch = "weather-1",
            ),
            fixture(
                "same timer target is corrected",
                listOf(timer42),
                "CONTINUE",
                "timer-42",
                slotName = "duration_minutes",
                slotValue = "15",
                dispatch = "timer-42",
            ),
            fixture("ambiguous two-timer target clarifies", listOf(timer42, timer43), "CLARIFY", null),
            fixture("genuine topic change while slot pending", listOf(reminder), "REPLACE", "reminder-1"),
            fixture("unrelated request after completed lookup is a new topic", listOf(sydneyWeather), "NEW_TOPIC", null),
            fixture(
                "schema-valid wrong timer target",
                listOf(timer42, timer43),
                "CONTINUE",
                "timer-43",
                slotName = "duration_minutes",
                slotValue = "15",
                expectedTransition = "CONTINUE",
                expectedActionId = "timer-42",
                dispatch = "timer-42",
                semanticExpected = false,
            ),
            fixture(
                "ambiguous two-timer target arbitrarily chosen",
                listOf(timer42, timer43),
                "CONTINUE",
                "timer-42",
                slotName = "duration_minutes",
                slotValue = "15",
                expectedTransition = "CLARIFY",
                expectedActionId = null,
                semanticExpected = false,
            ),
            fixture(
                "wrong transition REPLACE for a continuation",
                listOf(wikiCheck, sydneyWeather),
                "REPLACE",
                "wiki-check-1",
                expectedTransition = "CONTINUE",
                dispatch = "wiki-check-1",
                semanticExpected = false,
            ),
            fixture(
                "continuation of the wrong held action",
                listOf(wikiCheck, sydneyWeather),
                "CONTINUE",
                "weather-1",
                expectedTransition = "CONTINUE",
                expectedActionId = "wiki-check-1",
                dispatch = "wiki-check-1",
                semanticExpected = false,
            ),
            fixture(
                "right action but wrong slot value does not dispatch",
                listOf(timer42),
                "CONTINUE",
                "timer-42",
                slotName = "duration_minutes",
                slotValue = "20",
                expectedSlotValue = "15",
                dispatch = "timer-42",
                slotExpected = false,
            ),
            Fixture(
                name = "display label as action id is outside the enum",
                held = listOf(reminder),
                raw = """{"transition":"CANCEL","action_id":"Pending reminder: call the blinds people on Monday","slot_name":null,"slot_value":null}""",
                expected = Expected(Transition.CANCEL, "reminder-1"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "previous free-text subject contract is structurally invalid",
                held = listOf(reminder),
                raw = """{"transition":"CANCEL","subject":"call the blinds people on Monday","action_id":"reminder-1","slot_name":null,"slot_value":null}""",
                expected = Expected(Transition.CANCEL, "reminder-1"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "slot name not offered for the turn",
                held = listOf(reminder),
                raw = """{"transition":"CONTINUE","action_id":"reminder-1","slot_name":"duration_minutes","slot_value":"15"}""",
                expected = Expected(Transition.CONTINUE, "reminder-1", "time", "9:00"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            invalid("missing required field", """{"transition":"CANCEL","action_id":"reminder-1","slot_name":null}"""),
            invalid("malformed response", "not-json"),
            invalid("truncated response", """{"transition":"CANCEL","action_id":"remin"""),
            invalid("non-string slot value", """{"transition":"CANCEL","action_id":"reminder-1","slot_name":"time","slot_value":9}"""),
            invalid("unexpected property", """{"transition":"CANCEL","action_id":"reminder-1","slot_name":null,"slot_value":null,"tool":"execute"}"""),
            invalid("transition outside the enum", """{"transition":"EXECUTE","action_id":"reminder-1","slot_name":null,"slot_value":null}"""),
        )

        var structuralPasses = 0
        var semanticPasses = 0
        var unsafeDispatches = 0
        fixtures.forEach { fixture ->
            val actual = reduce(fixture.raw, fixture.held, fixture.expected)
            assertEquals(fixture.structuralExpected, actual.structuralValid, fixture.name)
            assertEquals(fixture.semanticExpected, actual.semanticCorrect, fixture.name)
            assertEquals(fixture.slotExpected, actual.slotCorrect, fixture.name)
            assertEquals(fixture.transitionExpected, actual.transition, fixture.name)
            assertEquals(fixture.dispatchExpected, actual.dispatchTarget, fixture.name)
            if (actual.semanticCorrect && fixture.expected.actionId != null) {
                assertEquals(
                    fixture.held.single { it.id == fixture.expected.actionId }.subject,
                    actual.resolvedSubject,
                    "subject is resolved from app state by id: ${fixture.name}",
                )
            }
            if (actual.structuralValid) structuralPasses++
            if (actual.semanticCorrect) semanticPasses++
            if (actual.dispatchTarget != null &&
                (!actual.semanticCorrect || !actual.slotCorrect || actual.transition != Transition.CONTINUE ||
                    actual.dispatchTarget != fixture.expected.dispatchTarget)
            ) {
                unsafeDispatches++
            }
        }
        assertEquals(13, structuralPasses, "structural pass count is independent of semantic pass count")
        assertEquals(9, semanticPasses)
        assertEquals(0, unsafeDispatches)
    }

    @Test
    fun `valid pending reminder reply completes deterministically without semantic resolver`() {
        val manager = SlotFillerManager(IntentContractRegistry())
        manager.startSlotFill(
            "reminder-conversation",
            PendingSlotRequest(
                intentName = "add_reminder",
                existingParams = mapOf("item" to "call the blinds people", "day" to "monday"),
                missingSlot = SlotSpec("time", "What time on Monday should I remind you?"),
            ),
        )

        var resolverCalls = 0
        val result = manager.onUserReply("reminder-conversation", "9am")
        if (result !is SlotFillResult.Completed) resolverCalls++

        assertTrue(result is SlotFillResult.Completed)
        assertEquals(
            mapOf("item" to "call the blinds people", "day" to "monday", "time" to "9:00"),
            (result as SlotFillResult.Completed).params,
        )
        assertFalse(manager.hasPendingFor("reminder-conversation"))
        assertEquals(0, resolverCalls, "successful deterministic slot fill must bypass the model")
    }

    @Test
    fun `failed reminder-slot replies reach the host oracle without mutating manager state`() {
        val cases = listOf(
            fixture("cancel pending reminder", listOf(reminder), "CANCEL", "reminder-1", userReply = "cancel"),
            fixture(
                "unrelated weather replaces pending reminder",
                listOf(reminder),
                "REPLACE",
                "reminder-1",
                userReply = "What's the weather in Sydney?",
            ),
            fixture(
                "same pending reminder continues after a non-time reply",
                listOf(reminder),
                "CONTINUE",
                "reminder-1",
                userReply = "I'm still talking about the same reminder",
            ),
            fixture("ambiguous reply asks targeted clarification", listOf(reminder), "CLARIFY", "reminder-1", userReply = "which one?"),
        )

        cases.forEach { fixture ->
            val validationRegistry = SlotValidationRegistry().apply {
                register("add_reminder", "time", AlarmTimeValidator)
            }
            val manager = SlotFillerManager(IntentContractRegistry(), validationRegistry)
            manager.startSlotFill(
                "reminder-conversation",
                PendingSlotRequest(
                    intentName = "add_reminder",
                    existingParams = mapOf("item" to "call the blinds people", "day" to "monday"),
                    missingSlot = SlotSpec("time", "What time on Monday should I remind you?"),
                ),
            )

            val userReply = requireNotNull(fixture.userReply)
            val slotResult = manager.onUserReply("reminder-conversation", userReply)
            assertTrue(slotResult is SlotFillResult.InvalidSlot, fixture.name)
            assertTrue(manager.hasPendingFor("reminder-conversation"), "invalid slot remains manager-owned")

            val decision = reduce(fixture.raw, fixture.held, fixture.expected)
            assertTrue(decision.structuralValid, fixture.name)
            assertTrue(decision.semanticCorrect, fixture.name)
            assertEquals(fixture.transitionExpected, decision.transition, fixture.name)
            assertEquals(reminder.subject, decision.resolvedSubject, fixture.name)
            assertNull(decision.dispatchTarget, "transition proposals never execute a tool")
            assertTrue(manager.hasPendingFor("reminder-conversation"), "proposal evaluation does not mutate slot-manager state")
        }
    }

    @Test
    fun `duplicate and stale tool results cannot complete or dispatch a current action`() {
        val ledger = ResultLedger(actionId = "action-1", revision = 4)
        assertTrue(ledger.accept(actionId = "action-1", revision = 4, callId = "call-1"))
        assertFalse(ledger.accept(actionId = "action-1", revision = 4, callId = "call-1"), "duplicate callback")
        assertFalse(ledger.accept(actionId = "action-1", revision = 3, callId = "late-call"), "stale revision")
        assertFalse(ledger.accept(actionId = "superseded-action", revision = 5, callId = "late-call-2"), "wrong action")
        assertEquals(setOf("call-1"), ledger.acceptedCallIds)
    }

    private fun fixture(
        name: String,
        held: List<HeldAction>,
        transition: String,
        actionId: String?,
        slotName: String? = null,
        slotValue: String? = null,
        expectedTransition: String = transition,
        expectedActionId: String? = actionId,
        expectedSlotValue: String? = slotValue,
        dispatch: String? = null,
        semanticExpected: Boolean = true,
        slotExpected: Boolean = true,
        userReply: String? = null,
    ) = Fixture(
        name = name,
        held = held,
        raw = response(transition, actionId, slotName, slotValue),
        expected = Expected(Transition.valueOf(expectedTransition), expectedActionId, slotName, expectedSlotValue, dispatch),
        semanticExpected = semanticExpected,
        slotExpected = slotExpected,
        userReply = userReply,
    )

    private fun invalid(name: String, raw: String) = Fixture(
        name = name,
        held = listOf(reminder),
        raw = raw,
        expected = Expected(Transition.CANCEL, "reminder-1"),
        structuralExpected = false,
        semanticExpected = false,
    )

    private fun response(transition: String, actionId: String?, slotName: String?, slotValue: String?): String =
        JsonObject().apply {
            addProperty("transition", transition)
            addNullable("action_id", actionId)
            addNullable("slot_name", slotName)
            addNullable("slot_value", slotValue)
        }.toString()

    private fun JsonObject.addNullable(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun enumValues(properties: JsonObject, key: String): List<String?> =
        properties.getAsJsonObject(key).getAsJsonArray("enum").map { if (it.isJsonNull) null else it.asString }

    /** Mirrors the device probe's per-turn schema builder. */
    private fun schemaFor(held: List<HeldAction>): String {
        fun nullableEnum(values: List<String>) = JsonObject().apply {
            add("enum", JsonArray().apply { values.forEach { add(JsonPrimitive(it)) }; add(JsonNull.INSTANCE) })
        }
        val properties = JsonObject().apply {
            add("transition", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { Transition.entries.forEach { add(it.name) } })
            })
            add("action_id", nullableEnum(held.map { it.id }))
            add("slot_name", nullableEnum(held.flatMap { it.slots }.distinct()))
            add("slot_value", JsonObject().apply {
                add("type", JsonArray().apply { add("string"); add("null") })
            })
        }
        return JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("required", JsonArray().apply { properties.keySet().forEach { add(it) } })
            add("properties", properties)
        }.toString()
    }

    /** Mirrors the device probe's prompt rendering: opaque id plus plain description. */
    private fun renderHeldActions(held: List<HeldAction>): String = buildString {
        appendLine("Held actions (most recent first):")
        held.forEach { action ->
            val slots = if (action.slots.isEmpty()) "" else " (slots: ${action.slots.joinToString()})"
            appendLine("- id ${action.id} [${if (action.pending) "pending" else "completed"}]: ${action.description}$slots")
        }
    }

    private fun reduce(raw: String, held: List<HeldAction>, expected: Expected): Result {
        val proposal = parseStructurally(raw, held)
            ?: return Result(false, false, true, Transition.CLARIFY, null, null)
        val semanticallyCorrect = proposal.transition == expected.transition && proposal.actionId == expected.actionId
        val slotCorrect = proposal.slotName == expected.slotName &&
            proposal.slotValue?.normalized() == expected.slotValue?.normalized()
        val safeTransition = if (semanticallyCorrect && slotCorrect) proposal.transition else Transition.CLARIFY
        val dispatch = if (safeTransition == Transition.CONTINUE) expected.dispatchTarget else null
        return Result(
            structuralValid = true,
            semanticCorrect = semanticallyCorrect,
            slotCorrect = slotCorrect,
            transition = safeTransition,
            dispatchTarget = dispatch,
            resolvedSubject = held.firstOrNull { it.id == proposal.actionId }?.subject,
        )
    }

    private fun parseStructurally(raw: String, held: List<HeldAction>): Proposal? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.keySet() != setOf("transition", "action_id", "slot_name", "slot_value")) return null
        fun nullableString(key: String): String? {
            val value = root.get(key)
            if (value.isJsonNull) return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        val transition = Transition.valueOf(nullableString("transition") ?: return null)
        val actionId = nullableString("action_id")
        if (actionId != null && held.none { it.id == actionId }) return null
        val slotName = nullableString("slot_name")
        if (slotName != null && held.none { slotName in it.slots }) return null
        Proposal(transition, actionId, slotName, nullableString("slot_value"))
    }.getOrNull()

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private class ResultLedger(private val actionId: String, private val revision: Int) {
        val acceptedCallIds = mutableSetOf<String>()

        fun accept(actionId: String, revision: Int, callId: String): Boolean {
            if (actionId != this.actionId || revision != this.revision) return false
            return acceptedCallIds.add(callId)
        }
    }
}
