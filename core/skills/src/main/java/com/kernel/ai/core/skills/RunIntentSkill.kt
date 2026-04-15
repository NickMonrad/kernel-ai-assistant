package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.NativeIntentHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gateway skill that mirrors Google AI Edge Gallery's `run_intent` pattern.
 *
 * All native Android device actions are routed through this single skill so the model only
 * needs to know ONE function name for all device operations. The [NativeIntentHandler] then
 * maps [intent_name] to the concrete Android API or system Intent.
 *
 * Additional params (time, subject, body, message, label, day) are passed alongside
 * intent_name in the tool call and forwarded to the handler as-is.
 * For set_alarm, pass `time` as the user said it (e.g. "10pm", "9:30am") — NativeIntentHandler
 * runs it through resolveTime() so no 12h→24h conversion is needed from the model.
 */
@Singleton
class RunIntentSkill @Inject constructor(
    private val handler: NativeIntentHandler,
) : Skill {

    override val name = "run_intent"
    override val description =
        "Perform a native Android device action. Use for flashlight control, sending email, " +
            "sending SMS, setting an alarm (supports optional day name for tomorrow/weekday alarms), " +
            "setting a countdown timer, creating a calendar event, or toggling Do Not Disturb mode. " +
            "For alarms: pass the time exactly as the user said it using the 'time' parameter (e.g. time:\"10pm\", time:\"9:30am\", time:\"22:00\"). " +
            "For calendar events, date accepts YYYY-MM-DD or relative terms like 'tomorrow', 'next wednesday'."

    override val schema = SkillSchema(
        parameters = mapOf(
            "intent_name" to SkillParameter(
                type = "string",
                description = "The action to perform.",
                enum = listOf(
                    "toggle_flashlight_on",
                    "toggle_flashlight_off",
                    "send_email",
                    "send_sms",
                    "set_alarm",
                    "set_timer",
                    "create_calendar_event",
                    "toggle_dnd_on",
                    "toggle_dnd_off",
                ),
            ),
        ),
        required = listOf("intent_name"),
    )

    override val examples: List<String> = listOf(
        "Flashlight on → runIntent(intentName=\"toggle_flashlight_on\", parameters=\"{}\")",
        "Send email → runIntent(intentName=\"send_email\", parameters='{\"subject\":\"Hi\",\"body\":\"Text\"}')",
        "Set alarm 10pm → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"10pm\"}')",
        "Set alarm 9pm called dinner → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"9pm\",\"label\":\"dinner\"}')",
        "Set alarm Monday 7am → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"7am\",\"day\":\"monday\"}')",
        "Set alarm tomorrow 8am called gym → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"8am\",\"day\":\"tomorrow\",\"label\":\"gym\"}')",
        "Set alarm 6am labeled workout → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"6am\",\"label\":\"workout\"}')",
        "Set timer 3min → runIntent(intentName=\"set_timer\", parameters='{\"duration_seconds\":\"180\"}')",
        "Calendar event → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Lunch\",\"date\":\"2026-04-15\",\"time\":\"12:30\"}')",
        "Turn on DND → runIntent(intentName=\"toggle_dnd_on\", parameters=\"{}\")",
        "Turn off DND → runIntent(intentName=\"toggle_dnd_off\", parameters=\"{}\")",
    )

    override val fullInstructions: String = buildString {
        appendLine("run_intent: Perform a native Android device action.")
        appendLine()
        appendLine("Parameters (pass as JSON in the 'parameters' argument):")
        appendLine("- intent_name (required, string): The action to perform.")
        appendLine("  Options: toggle_flashlight_on, toggle_flashlight_off, send_email, send_sms,")
        appendLine("           set_alarm, set_timer, create_calendar_event, toggle_dnd_on, toggle_dnd_off")
        appendLine("- time (string): Pass exactly as user said — e.g. \"10pm\", \"9:30am\", \"22:00\"")
        appendLine("- day (string): Optional day name — e.g. \"tomorrow\", \"monday\", \"next friday\"")
        appendLine("- label (string): Optional alarm label. Extract from phrases like 'called X', 'named X', 'labeled X', 'label X', 'with label X'. Label can appear before or after the time (e.g. 'alarm at 9pm called dinner' → label:\"dinner\", 'alarm labeled gym at 8am' → label:\"gym\")")
        appendLine("- duration_seconds (string): Timer duration in seconds (e.g. \"180\" for 3 min)")
        appendLine("- subject, body (string): For send_email")
        appendLine("- message, phone (string): For send_sms")
        appendLine("- title (string): Calendar event title")
        appendLine("- date (string): Calendar date as YYYY-MM-DD or relative (\"tomorrow\", \"next wednesday\")")
        appendLine("- duration_minutes (string): Optional calendar event duration")
        appendLine()
        appendLine("Rules:")
        appendLine("Alarm rule: whenever the user says 'set alarm', 'set an alarm', 'alarm for', 'alarm at',")
        appendLine("'wake me up at', or 'remind me at [specific clock time]' — call runIntent")
        appendLine("with intentName=set_alarm. Pass time EXACTLY as user said (e.g. time:\"10pm\").")
        appendLine("'Remind me at [specific time]' is an alarm (set_alarm).")
        appendLine("If the user specifies a day (e.g. 'tomorrow', 'next Monday'), include")
        appendLine("day=<day_value> passing it exactly as said (e.g. day:\"tomorrow\", day:\"monday\").")
        appendLine("NEVER output alarm confirmation text — only the tool call.")
        appendLine("NOTE: 'remind me in X minutes' is a timer (set_timer), NOT an alarm.")
        appendLine()
        appendLine("Calendar rule: 'add calendar entry', 'create calendar event', 'add event',")
        appendLine("'schedule [topic] on [date]' → runIntent with intentName=create_calendar_event.")
        appendLine("Resolve relative dates (tomorrow, next Friday) to YYYY-MM-DD. Pass time as HH:MM.")
        appendLine("NEVER confirm event was created without calling the tool.")
        appendLine("'remind me in X minutes/seconds' is set_timer, NOT create_calendar_event.")
        appendLine()
        appendLine("DND rule: 'turn on do not disturb', 'enable DND', 'silence notifications' →")
        appendLine("runIntent with intentName=toggle_dnd_on.")
        appendLine("'turn off do not disturb', 'disable DND', 'allow notifications again' →")
        appendLine("runIntent with intentName=toggle_dnd_off.")
        appendLine("If the app needs permission, it will open the settings page automatically.")
        appendLine()
        appendLine("Examples:")
        examples.forEach { appendLine("  $it") }
    }

    override suspend fun execute(call: SkillCall): SkillResult {
        val intentName = call.arguments["intent_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: intent_name.")
        val extraParams = call.arguments - "intent_name"
        return handler.handle(intentName, extraParams)
    }
}
