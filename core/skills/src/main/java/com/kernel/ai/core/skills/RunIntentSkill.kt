package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.NativeIntentHandler
import javax.inject.Inject
import javax.inject.Singleton

private val STR get() = "<|" + "\"" + "|>"

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
            "setting a countdown timer, or creating a calendar event. " +
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
                ),
            ),
        ),
        required = listOf("intent_name"),
    )

    override val examples: List<String> = listOf(
        "Flashlight on:   <|tool_call>call:run_intent{intent_name:${STR}toggle_flashlight_on${STR}}<tool_call|>",
        "Flashlight off:  <|tool_call>call:run_intent{intent_name:${STR}toggle_flashlight_off${STR}}<tool_call|>",
        "Send email:      <|tool_call>call:run_intent{intent_name:${STR}send_email${STR},subject:${STR}Subject${STR},body:${STR}Body${STR}}<tool_call|>",
        "Send SMS:        <|tool_call>call:run_intent{intent_name:${STR}send_sms${STR},message:${STR}Hello${STR}}<tool_call|>",
        "Set alarm 7:30:         <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}7:30am${STR}}<tool_call|>",
        "Set alarm 10pm:         <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}10pm${STR}}<tool_call|>",
        "Set alarm 9:30pm:       <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}9:30pm${STR}}<tool_call|>",
        "Remind me at 9am:       <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}9am${STR}}<tool_call|>",
        "Remind me at 09:05:     <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}09:05${STR}}<tool_call|>",
        "Set alarm with label:   <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}7:30am${STR},label:${STR}Wake Up${STR}}<tool_call|>",
        "Set alarm tomorrow 8am: <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}8am${STR},day:${STR}tomorrow${STR}}<tool_call|>",
        "Set alarm next monday:  <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}7am${STR},day:${STR}monday${STR}}<tool_call|>",
        "Set alarm Friday 10pm:  <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},time:${STR}10pm${STR},day:${STR}friday${STR}}<tool_call|>",
        "Set timer 3 min: <|tool_call>call:run_intent{intent_name:${STR}set_timer${STR},duration_seconds:${STR}180${STR}}<tool_call|>",
        "Add calendar event: <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Team lunch${STR},date:${STR}2026-04-15${STR},time:${STR}12:30${STR}}<tool_call|>",
        "Add all-day event:  <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Conference${STR},date:${STR}2026-04-20${STR}}<tool_call|>",
        "Add reminder date:  <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Call dentist${STR},date:${STR}2026-04-18${STR},time:${STR}09:00${STR},duration_minutes:${STR}30${STR}}<tool_call|>",
        "Add event next wednesday: <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Go to gym${STR},date:${STR}next wednesday${STR},time:${STR}08:00${STR}}<tool_call|>",
        "Add event tomorrow:  <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Dentist${STR},date:${STR}tomorrow${STR},time:${STR}09:00${STR}}<tool_call|>",
    )

    override val fullInstructions: String = buildString {
        appendLine("run_intent: Perform a native Android device action.")
        appendLine()
        appendLine("Parameters:")
        appendLine("- intent_name (required, string): The action to perform.")
        appendLine("  Options: toggle_flashlight_on, toggle_flashlight_off, send_email, send_sms,")
        appendLine("           set_alarm, set_timer, create_calendar_event")
        appendLine("- time (string): Pass exactly as user said — e.g. \"10pm\", \"9:30am\", \"22:00\"")
        appendLine("- day (string): Optional day name — e.g. \"tomorrow\", \"monday\", \"next friday\"")
        appendLine("- label (string): Optional alarm label")
        appendLine("- duration_seconds (string): Timer duration in seconds (e.g. \"180\" for 3 min)")
        appendLine("- subject, body (string): For send_email")
        appendLine("- message, phone (string): For send_sms")
        appendLine("- title (string): Calendar event title")
        appendLine("- date (string): Calendar date as YYYY-MM-DD or relative (\"tomorrow\", \"next wednesday\")")
        appendLine("- duration_minutes (string): Optional calendar event duration")
        appendLine()
        appendLine("Rules:")
        appendLine("Alarm rule: whenever the user says 'set alarm', 'alarm for', 'alarm at',")
        appendLine("'wake me up at', or 'remind me at [specific clock time]' — call run_intent")
        appendLine("with intent_name=set_alarm. Pass time EXACTLY as user said (e.g. time:\"10pm\").")
        appendLine("NEVER output alarm confirmation text — only the tool call token.")
        appendLine("NOTE: 'remind me in X minutes' is a timer (set_timer), NOT an alarm.")
        appendLine()
        appendLine("Calendar rule: 'add calendar entry', 'create calendar event', 'add event',")
        appendLine("'schedule [topic] on [date]' → run_intent with intent_name=create_calendar_event.")
        appendLine("Resolve relative dates (tomorrow, next Friday) to YYYY-MM-DD. Pass time as HH:MM.")
        appendLine("NEVER confirm event was created without calling the tool.")
        appendLine("'remind me in X minutes/seconds' is set_timer, NOT create_calendar_event.")
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
