package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.NativeIntentHandler
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import javax.inject.Inject
import javax.inject.Singleton

private data class RunIntentModelAction(val name: String, val parameterHelp: String)
private data class RunIntentModelCategory(val title: String, val actions: List<RunIntentModelAction>)

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

    companion object {
        internal const val GET_DATE_DIFF_PARAMETER_HELP =
            "target_date (required YYYY-MM-DD), from_date? (earlier YYYY-MM-DD)"
        private val MODEL_ACTION_CATEGORIES = listOf(
            RunIntentModelCategory("FLASHLIGHT", listOf(
                RunIntentModelAction("toggle_flashlight_on", ""),
                RunIntentModelAction("toggle_flashlight_off", ""),
            )),
            RunIntentModelCategory("COMMUNICATION", listOf(
                RunIntentModelAction("send_email", "contact, subject, body"),
                RunIntentModelAction("send_sms", "contact|phone, message"),
                RunIntentModelAction("make_call", "contact"),
            )),
            RunIntentModelCategory("ALARMS AND TIMERS", listOf(
                RunIntentModelAction("set_alarm", "time, day?, label?"),
                RunIntentModelAction("cancel_alarm", "label?"),
                RunIntentModelAction("set_timer", "duration_seconds, label?"),
                RunIntentModelAction("cancel_timer", ""),
                RunIntentModelAction("cancel_timer_named", "name? (omit to cancel all)"),
                RunIntentModelAction("list_timers", ""),
                RunIntentModelAction("get_timer_remaining", "name?"),
            )),
            RunIntentModelCategory("STOPWATCH", listOf(
                RunIntentModelAction("start_stopwatch", ""),
                RunIntentModelAction("pause_stopwatch", ""),
                RunIntentModelAction("resume_stopwatch", ""),
                RunIntentModelAction("lap_stopwatch", ""),
                RunIntentModelAction("reset_stopwatch", ""),
                RunIntentModelAction("get_stopwatch_status", ""),
            )),
            RunIntentModelCategory("CALENDAR", listOf(
                RunIntentModelAction("create_calendar_event", "title, date, time, duration_minutes?, description?, attendees?"),
            )),
            RunIntentModelCategory("INFO QUERIES", listOf(
                RunIntentModelAction("get_battery", ""),
                RunIntentModelAction("get_time", ""),
                RunIntentModelAction("get_date", ""),
            )),
            RunIntentModelCategory("DO NOT DISTURB", listOf(
                RunIntentModelAction("toggle_dnd_on", ""),
                RunIntentModelAction("toggle_dnd_off", ""),
            )),
            RunIntentModelCategory("VOLUME", listOf(
                RunIntentModelAction("set_volume", "value, is_percent"),
            )),
            RunIntentModelCategory("SYSTEM SETTINGS", listOf(
                RunIntentModelAction("toggle_wifi", "state"),
                RunIntentModelAction("toggle_bluetooth", "state"),
                RunIntentModelAction("toggle_airplane_mode", ""),
                RunIntentModelAction("toggle_hotspot", ""),
                RunIntentModelAction("set_brightness", "value|direction, is_percent?"),
            )),
            RunIntentModelCategory("MEDIA PLAYBACK", listOf(
                RunIntentModelAction("play_media", "query, artist?"),
                RunIntentModelAction("play_media_album", "album, artist?"),
                RunIntentModelAction("play_media_playlist", "playlist"),
                RunIntentModelAction("play_youtube", "query"),
                RunIntentModelAction("play_spotify", "query"),
                RunIntentModelAction("play_plexamp", "query"),
                RunIntentModelAction("play_youtube_music", "query"),
                RunIntentModelAction("play_netflix", "query"),
                RunIntentModelAction("play_plex", "title"),
                RunIntentModelAction("pause_media", ""),
                RunIntentModelAction("stop_media", ""),
                RunIntentModelAction("next_track", ""),
                RunIntentModelAction("previous_track", ""),
            )),
            RunIntentModelCategory("PODCASTS", listOf(
                RunIntentModelAction("play_podcast", "show"),
                RunIntentModelAction("podcast_skip_forward", "seconds?"),
                RunIntentModelAction("podcast_skip_back", "seconds?"),
                RunIntentModelAction("podcast_speed", "rate"),
            )),
            RunIntentModelCategory("NAVIGATION", listOf(
                RunIntentModelAction("navigate_to", "destination"),
                RunIntentModelAction("find_nearby", "query"),
            )),
            RunIntentModelCategory("APPS", listOf(
                RunIntentModelAction("open_app", "app_name"),
            )),
            RunIntentModelCategory("LISTS AND REMINDERS", listOf(
                RunIntentModelAction("create_list", "list_name"),
                RunIntentModelAction("add_to_list", "item, list_name"),
                RunIntentModelAction("bulk_add_to_list", "items[] (JSON strings), list_name? (defaults to shopping list; creates it if needed)"),
                RunIntentModelAction("get_list_items", "list_name? (defaults to shopping list)"),
                RunIntentModelAction("remove_from_list", "item, list_name?"),
                RunIntentModelAction("add_reminder", "item, day, time"),
            )),
            RunIntentModelCategory("DATE AND MATH", listOf(
                RunIntentModelAction("get_date_diff", GET_DATE_DIFF_PARAMETER_HELP),
                RunIntentModelAction("calculate_arithmetic", "expression"),
                RunIntentModelAction("convert_units", "value, from_unit, to_unit"),
                RunIntentModelAction("convert_cooking_measure", "amount, from_unit, ingredient, to_unit?"),
            )),
            RunIntentModelCategory("IMPORTANT DATES", listOf(
                RunIntentModelAction("save_important_date", "label, date"),
                RunIntentModelAction("list_important_dates", ""),
                RunIntentModelAction("remove_important_date", "label"),
            )),
            RunIntentModelCategory("NOTES", listOf(
                RunIntentModelAction("create_note", "content"),
                RunIntentModelAction("list_notes", ""),
            )),
        )

        internal val MODEL_CALLABLE_INTENTS =
            MODEL_ACTION_CATEGORIES.flatMap { category -> category.actions.map { it.name } }

        internal val MODEL_EXCLUDED_INTENTS = linkedMapOf(
            "get_weather" to "Dedicated top-level tool; use get_weather.",
            "get_system_info" to "Dedicated top-level tool; use get_system_info.",
            "save_memory" to "Dedicated top-level tool; use save_memory.",
            "convert_currency" to "Dedicated top-level tool; use convert_currency.",
            "smart_home_on" to "Non-callable stub; integration unavailable.",
            "smart_home_off" to "Non-callable stub; integration unavailable.",
        )
    }

    private val validationRegistry: SlotValidationRegistry by lazy { SlotValidationRegistry() }
    override val name = "run_intent"
    override val description =
        "Native actions, lists/notes; weather/system/memory/currency use dedicated tools."

    override val schema = SkillSchema(
        parameters = mapOf(
            "intent_name" to SkillParameter(
                type = "string",
                description = "The user-callable action to perform.",
                enum = MODEL_CALLABLE_INTENTS,
            ),
        ),
        required = listOf("intent_name"),
    )

    override val examples: List<String> = listOf(
        "Check stopwatch → runIntent(intentName=\"get_stopwatch_status\", parameters=\"{}\")",
        "Set timer → runIntent(intentName=\"set_timer\", parameters='{\"duration_seconds\":\"180\"}')",
        "Bulk list → runIntent(intentName=\"bulk_add_to_list\", parameters='{\"items\":[\"apples\",\"rice\"],\"list_name\":\"groceries\"}')",
        "Calendar event → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Dinner\",\"date\":\"tomorrow\",\"time\":\"19:00\"}')",
    )

    override val fullInstructions: String = buildString {
        appendLine("run_intent: Execute a supported native Android action or local calculation.")
        appendLine("Parameters: use the exact catalogue intent_name and a JSON object string with the shown field names.")
        appendLine("If instructions are requested first, call load_skill(\"run_intent\") first; wait for success.")
        appendLine("After successful load_skill, call run_intent once for the same request before replying; do not stop, reload, summarize, or ask the user to repeat.")
        appendLine("For bulk_add_to_list, call once with all items as a JSON array and the requested list_name.")
        appendLine()
        MODEL_ACTION_CATEGORIES.forEach { category ->
            val actions = category.actions.joinToString(", ") { action ->
                "${action.name}(${action.parameterHelp})"
            }
            appendLine("${category.title}: $actions")
        }
        appendLine("Excluded:")
        MODEL_EXCLUDED_INTENTS.forEach { (intent, reason) -> appendLine("$intent: $reason") }
        appendLine("Alarm: use set_alarm for clock-time alarms, wake-ups, or reminders at a time; pass time/day as said. Reminders in X minutes/seconds use set_timer.")
        appendLine("Calendar: create_calendar_event for event requests; preserve relative dates, use HH:MM, and set duration_minutes from start/end.")
        appendLine("Never claim alarm/calendar success without a successful tool result; for alarms, issue only the tool call.")
        appendLine("Lists and notes: retrieve saved contents with get_list_items/list_notes; never use search_memory to answer list-content requests.")
        appendLine()
        appendLine("Examples:")
        examples.forEach { appendLine(it) }
    }

    override val compactInstructions: String = buildString {
        appendLine("run_intent: use one exact catalogue intent_name and a JSON object string of parameters.")
        appendLine("After successful load_skill, call run_intent once for the original request before replying.")
        appendLine("bulk_add_to_list: include all items in one JSON array and the requested list_name.")
        appendLine("Callable intents and parameter hints:")
        MODEL_ACTION_CATEGORIES.forEach { category ->
            appendLine(
                category.actions.joinToString("; ") { action ->
                    if (action.parameterHelp.isBlank()) {
                        action.name
                    } else {
                        "${action.name}(${action.parameterHelp})"
                    }
                },
            )
        }
    }

    // Success: action result — delegates to NativeIntentHandler; LLM narration appropriate
    override suspend fun execute(call: SkillCall): SkillResult {
        val intentName = call.arguments["intent_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: intent_name.")
        val extraParams = call.arguments - "intent_name"
        // Validate slot values before dispatching — catches invalid already-populated
        // params from direct model tool calls (KernelAIToolSet.runIntent) that bypass
        // ChatViewModel.validateBeforeDispatch, and recovery/anaphoric paths
        val invalid = validationRegistry.validateParams(intentName, extraParams)
        if (invalid != null) {
            val message = invalid.errorMessage ?: "Invalid value for $intentName. Please try again."
            return SkillResult.Failure("$name/$intentName", message)
        }
        return handler.handle(intentName, extraParams)
    }
}
