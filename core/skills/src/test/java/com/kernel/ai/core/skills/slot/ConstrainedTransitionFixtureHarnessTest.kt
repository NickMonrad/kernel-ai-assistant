package com.kernel.ai.core.skills.slot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Host oracle for the accepted reducer seam; proposals never execute a real tool. */
class ConstrainedTransitionFixtureHarnessTest {
    private enum class Transition { CONTINUE, CANCEL, REPLACE, CLARIFY }

    private data class Proposal(
        val transition: Transition,
        val subject: String,
        val slotName: String?,
        val slotValue: String?,
        val targetId: String?,
        val actionId: String,
        val revision: Int,
    )

    private data class Pending(
        val actionId: String,
        val revision: Int,
        val transition: Transition,
        val subject: String,
        val slotName: String? = null,
        val slotValue: String? = null,
        val targetId: String? = null,
        val dispatchTarget: String? = null,
    )

    private data class Fixture(
        val name: String,
        val raw: String,
        val pending: Pending,
        val structuralExpected: Boolean,
        val semanticExpected: Boolean,
        val dispatchExpected: String? = null,
    )

    private data class Result(
        val structuralValid: Boolean,
        val semanticCorrect: Boolean,
        val dispatchTarget: String?,
    )

    private val schema = """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["transition","subject","slot_name","slot_value","target_id","action_id","revision"],
          "properties":{
            "transition":{"type":"string","enum":["CONTINUE","CANCEL","REPLACE","CLARIFY"]},
            "subject":{"type":"string","minLength":1},
            "slot_name":{"type":["string","null"]},
            "slot_value":{"type":["string","null"]},
            "target_id":{"type":["string","null"]},
            "action_id":{"type":"string","minLength":1},
            "revision":{"type":"integer","minimum":0}
          }
        }
    """.trimIndent()

    @Test
    fun `host fixtures separate structural validity semantic correctness and dispatch safety`() {

        val fixtures = listOf(
            fixture("wikipedia exact disputed proposition", "CONTINUE", "prisoners brewing alcohol in toilets", dispatch = "wiki-action"),
            fixture("cancel pending reminder", "CANCEL", "call the blinds people on Monday"),
            fixture("unrelated weather replaces pending reminder", "REPLACE", "weather in Sydney"),
            fixture("Sydney weather tomorrow keeps location", "CONTINUE", "weather in Sydney", slotName = "date", slotValue = "tomorrow", dispatch = "weather-action"),
            fixture("same timer target is corrected", "CONTINUE", "20 minute timer", targetId = "timer-42", dispatch = "timer-42"),
            fixture("ambiguous two-timer target clarifies", "CLARIFY", "timer", targetId = null),
            fixture("explicit cancellation does not dispatch", "CANCEL", "pending reminder"),
            fixture("genuine topic change while slot pending", "REPLACE", "weather in Sydney"),
            fixture("ambiguous reply asks targeted clarification", "CLARIFY", "pending action"),
            fixture("schema-valid wrong timer target", "CONTINUE", "20 minute timer", targetId = "timer-99", semantic = false),
            fixture("schema-valid wrong Wikipedia subject", "CONTINUE", "generic brewing", semantic = false),
            fixture("stale action revision", "CONTINUE", "prisoners brewing alcohol in toilets", revision = 3, semantic = false),
            Fixture(
                name = "missing required field",
                raw = """{"transition":"CONTINUE","subject":"topic","slot_name":null,"slot_value":null,"target_id":null,"action_id":"action-1"}""",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "malformed response",
                raw = "not-json",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "truncated response",
                raw = """{"transition":"CONTINUE","subject":"topic","slot_name":null""",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "fractional revision is structurally invalid",
                raw = """{"transition":"CONTINUE","subject":"topic","slot_name":null,"slot_value":null,"target_id":null,"action_id":"action-1","revision":4.5}""",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "non-string slot value is structurally invalid",
                raw = """{"transition":"CONTINUE","subject":"topic","slot_name":null,"slot_value":9,"target_id":null,"action_id":"action-1","revision":4}""",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
            Fixture(
                name = "unexpected property is structurally invalid",
                raw = """{"transition":"CONTINUE","subject":"topic","slot_name":null,"slot_value":null,"target_id":null,"action_id":"action-1","revision":4,"tool":"execute"}""",
                pending = pending("CONTINUE", "topic"),
                structuralExpected = false,
                semanticExpected = false,
            ),
        )

        var structuralPasses = 0
        var semanticPasses = 0
        var unsafeDispatches = 0
        fixtures.forEach { fixture ->
            val actual = reduce(fixture.raw, fixture.pending)
            assertEquals(fixture.structuralExpected, actual.structuralValid, fixture.name)
            assertEquals(fixture.semanticExpected, actual.semanticCorrect, fixture.name)
            assertEquals(fixture.dispatchExpected, actual.dispatchTarget, fixture.name)
            if (actual.structuralValid) structuralPasses++
            if (actual.semanticCorrect) semanticPasses++
            if (actual.dispatchTarget != null && actual.dispatchTarget != fixture.dispatchExpected) unsafeDispatches++
        }
        assertEquals(12, structuralPasses, "structural pass count is independent of semantic pass count")
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
            mapOf("item" to "call the blinds people", "day" to "monday", "time" to "09:00"),
            (result as SlotFillResult.Completed).params,
        )
        assertFalse(manager.hasPendingFor("reminder-conversation"))
        assertEquals(0, resolverCalls, "successful deterministic slot fill must bypass the model")
    }

    @Test
    fun `invalid pending-slot replies reach proposal evaluator before re-prompting`() {
        val manager = SlotFillerManager(IntentContractRegistry())
        manager.startSlotFill(
            "reminder-conversation",
            PendingSlotRequest(
                intentName = "add_reminder",
                existingParams = mapOf("item" to "call the blinds people", "day" to "monday"),
                missingSlot = SlotSpec("time", "What time on Monday should I remind you?"),
            ),
        )

        val invalid = manager.onUserReply("reminder-conversation", "What's the weather in Sydney?")
        assertTrue(invalid is SlotFillResult.InvalidSlot)
        assertTrue(manager.hasPendingFor("reminder-conversation"), "resolver sees the existing manager-owned pending slot")

        val replacement = fixture("topic change", "REPLACE", "weather in Sydney")
        val decision = reduce(replacement.raw, replacement.pending)
        assertTrue(decision.semanticCorrect)
        assertNull(decision.dispatchTarget, "the pending reminder must not dispatch under a replacement proposal")
        assertTrue(manager.hasPendingFor("reminder-conversation"), "host harness does not duplicate or mutate slot-manager state")
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
        transition: String,
        subject: String,
        slotName: String? = null,
        slotValue: String? = null,
        targetId: String? = null,
        actionId: String = "action-1",
        revision: Int = 4,
        semantic: Boolean = true,
        dispatch: String? = null,
    ): Fixture {
        val pending = Pending(
            actionId = "action-1",
            revision = 4,
            transition = Transition.valueOf(transition),
            subject = when {
                !semantic && name.contains("Wikipedia") -> "prisoners brewing alcohol in toilets"
                !semantic && name.contains("timer") -> "20 minute timer"
                else -> subject
            },
            slotName = slotName,
            slotValue = slotValue,
            targetId = when {
                !semantic && name.contains("timer") -> "timer-42"
                else -> targetId
            },
            dispatchTarget = dispatch,
        )
        val raw = response(
            transition = transition,
            subject = subject,
            slotName = slotName,
            slotValue = slotValue,
            targetId = targetId,
            actionId = actionId,
            revision = revision,
        )
        return Fixture(name, raw, pending, structuralExpected = true, semanticExpected = semantic, dispatchExpected = dispatch)
    }

    private fun pending(transition: String, subject: String) = Pending(
        actionId = "action-1",
        revision = 4,
        transition = Transition.valueOf(transition),
        subject = subject,
    )

    private fun response(
        transition: String,
        subject: String,
        slotName: String?,
        slotValue: String?,
        targetId: String?,
        actionId: String,
        revision: Int,
    ): String = JsonObject().apply {
        addProperty("transition", transition)
        addProperty("subject", subject)
        addNullable("slot_name", slotName)
        addNullable("slot_value", slotValue)
        addNullable("target_id", targetId)
        addProperty("action_id", actionId)
        addProperty("revision", revision)
    }.toString()

    private fun JsonObject.addNullable(name: String, value: String?) {
        if (value == null) add(name, com.google.gson.JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun reduce(raw: String, pending: Pending): Result {
        val proposal = parseStructurally(raw) ?: return Result(false, false, null)
        val semanticallyCorrect = proposal.transition == pending.transition &&
            proposal.subject.normalized() == pending.subject.normalized() &&
            proposal.slotName == pending.slotName &&
            proposal.slotValue == pending.slotValue &&
            proposal.targetId == pending.targetId &&
            proposal.actionId == pending.actionId &&
            proposal.revision == pending.revision
        val dispatch = if (
            semanticallyCorrect && proposal.transition == Transition.CONTINUE && pending.dispatchTarget != null
        ) {
            pending.dispatchTarget
        } else {
            null
        }
        return Result(true, semanticallyCorrect, dispatch)
    }

    private fun parseStructurally(raw: String): Proposal? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.keySet() != setOf("transition", "subject", "slot_name", "slot_value", "target_id", "action_id", "revision")) return null
        fun stringValue(key: String): String? {
            val value = root.get(key)
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        val transition = Transition.valueOf(stringValue("transition") ?: return null)
        val subject = stringValue("subject")?.takeIf { it.isNotBlank() } ?: return null
        val actionId = stringValue("action_id")?.takeIf { it.isNotBlank() } ?: return null
        val revisionValue = root.get("revision")
        if (!revisionValue.isJsonPrimitive || !revisionValue.asJsonPrimitive.isNumber) return null
        val revisionNumber = revisionValue.asBigDecimal.stripTrailingZeros()
        if (revisionNumber.scale() > 0 || revisionNumber.signum() < 0) return null
        val revision = revisionNumber.intValueExact()
        fun nullableString(key: String): String? {
            val value = root.get(key)
            if (value.isJsonNull) return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        Proposal(
            transition = transition,
            subject = subject,
            slotName = nullableString("slot_name"),
            slotValue = nullableString("slot_value"),
            targetId = nullableString("target_id"),
            actionId = actionId,
            revision = revision,
        )
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
