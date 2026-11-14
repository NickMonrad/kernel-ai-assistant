package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.permissions.CapabilityKey
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads calendar events. Reachable as the `read_calendar_events` skill (quick-intent routing,
 * skill registry, load_skill) and as the `read_calendar_events` run_intent action (E4B tool calls).
 */
@Singleton
class ReadCalendarEventsSkill @Inject constructor(
    private val store: CalendarEventStore,
) : Skill {

    override val name = "read_calendar_events"
    override val description =
        "Reads the user's calendar events for today, tomorrow, this week, next week or the next 7 days, " +
            "or finds the next event matching a keyword (e.g. 'dentist'). Use for 'what's on my calendar', " +
            "'what do I have this week', 'when is my next dentist appointment'."
    override val schema = SkillSchema(
        parameters = mapOf(
            "range" to SkillParameter(
                type = "string",
                description = "Window to read. Defaults to today, or the next year when only a keyword is given.",
                enum = CalendarRange.entries.map { it.wireName },
            ),
            "keyword" to SkillParameter(
                type = "string",
                description = "Optional text to match in event title, calendar or location (e.g. 'dentist').",
            ),
        ),
    )

    override suspend fun execute(call: SkillCall): SkillResult = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        readCalendarEvents(
            store = store,
            params = call.arguments,
            today = now.atZone(zone).toLocalDate(),
            zone = zone,
            now = now,
        )
    }
}

/** Shared read path for the skill and the run_intent action. Returns a user-facing result, never throws. */
internal fun readCalendarEvents(
    store: CalendarEventStore,
    params: Map<String, String>,
    today: LocalDate,
    zone: ZoneId,
    now: Instant,
): SkillResult {
    if (!store.hasReadPermission()) {
        return SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.CalendarLookup,
            skillName = "read_calendar_events",
        )
    }
    val keyword = params["keyword"]?.trim()?.takeIf { it.isNotEmpty() }
    val rawRange = params["range"]?.takeIf { it.isNotBlank() }
    val range = CalendarRange.parse(rawRange)
    if (rawRange != null && range == null) {
        return SkillResult.Failure(
            skillName = "read_calendar_events",
            error = "I can read today, tomorrow, this week, next week or the next 7 days.",
        )
    }
    val window = CalendarEventReplies.readWindow(range, keyword != null, today, zone, now)
    val events = store
        .queryInstances(window.fromDate, window.toDateExclusive)
        .filter { CalendarEventReplies.isInWindow(it, window) }
    val reply = CalendarEventReplies.formatRead(range, keyword, events, zone)
    return SkillResult.DirectReply(content = reply, spokenSummary = reply)
}
