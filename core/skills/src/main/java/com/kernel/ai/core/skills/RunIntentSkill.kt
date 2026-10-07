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
        private val MODEL_ACTION_CATEGORIES = listOf(
            RunIntentModelCategory("FLASHLIGHT", listOf(
                RunIntentModelAction("toggle_flashlight_on", "no params"),
                RunIntentModelAction("toggle_flashlight_off", "no params"),
            )),
            RunIntentModelCategory("COMMUNICATION", listOf(
                RunIntentModelAction("send_email", "contact, subject, body"),
                RunIntentModelAction("send_sms", "contact or phone, message"),
                RunIntentModelAction("make_call", "contact"),
            )),
            RunIntentModelCategory("ALARMS AND TIMERS", listOf(
                RunIntentModelAction("set_alarm", "time, day (optional), label (optional)"),
                RunIntentModelAction("cancel_alarm", "label (optional)"),
                RunIntentModelAction("set_timer", "duration_seconds, label (optional)"),
                RunIntentModelAction("cancel_timer", "no params"),
                RunIntentModelAction("cancel_timer_named", "name (optional; omit to cancel all timers)"),
                RunIntentModelAction("list_timers", "no params"),
                RunIntentModelAction("get_timer_remaining", "name (optional)"),
            )),
            RunIntentModelCategory("STOPWATCH", listOf(
                RunIntentModelAction("start_stopwatch", "no params"),
                RunIntentModelAction("pause_stopwatch", "no params"),
                RunIntentModelAction("resume_stopwatch", "no params"),
                RunIntentModelAction("lap_stopwatch", "no params"),
                RunIntentModelAction("reset_stopwatch", "no params"),
                RunIntentModelAction("get_stopwatch_status", "no params"),
            )),
            RunIntentModelCategory("CALENDAR", listOf(
                RunIntentModelAction("create_calendar_event", "title, date, time, duration_minutes (optional), description (optional), attendees (optional)"),
            )),
            RunIntentModelCategory("INFO QUERIES", listOf(
                RunIntentModelAction("get_battery", "no params"),
                RunIntentModelAction("get_time", "no params"),
                RunIntentModelAction("get_date", "no params"),
            )),
            RunIntentModelCategory("DO NOT DISTURB", listOf(
                RunIntentModelAction("toggle_dnd_on", "no params"),
                RunIntentModelAction("toggle_dnd_off", "no params"),
            )),
            RunIntentModelCategory("VOLUME", listOf(
                RunIntentModelAction("set_volume", "value, is_percent"),
            )),
            RunIntentModelCategory("SYSTEM SETTINGS", listOf(
                RunIntentModelAction("toggle_wifi", "state (on or off)"),
                RunIntentModelAction("toggle_bluetooth", "state (on or off)"),
                RunIntentModelAction("toggle_airplane_mode", "no params"),
                RunIntentModelAction("toggle_hotspot", "no params"),
                RunIntentModelAction("set_brightness", "value or direction, is_percent (optional)"),
            )),
            RunIntentModelCategory("MEDIA PLAYBACK", listOf(
                RunIntentModelAction("play_media", "query, artist (optional)"),
                RunIntentModelAction("play_media_album", "album, artist (optional)"),
                RunIntentModelAction("play_media_playlist", "playlist"),
                RunIntentModelAction("play_youtube", "query"),
                RunIntentModelAction("play_spotify", "query"),
                RunIntentModelAction("play_plexamp", "query"),
                RunIntentModelAction("play_youtube_music", "query"),
                RunIntentModelAction("play_netflix", "query"),
                RunIntentModelAction("play_plex", "title"),
                RunIntentModelAction("pause_media", "no params"),
                RunIntentModelAction("stop_media", "no params"),
                RunIntentModelAction("next_track", "no params"),
                RunIntentModelAction("previous_track", "no params"),
            )),
            RunIntentModelCategory("PODCASTS", listOf(
                RunIntentModelAction("play_podcast", "show"),
                RunIntentModelAction("podcast_skip_forward", "seconds (optional)"),
                RunIntentModelAction("podcast_skip_back", "seconds (optional)"),
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
                RunIntentModelAction("bulk_add_to_list", "items (CSV or JSON array), list_name (optional)"),
                RunIntentModelAction("get_list_items", "list_name (optional; defaults to shopping list)"),
                RunIntentModelAction("remove_from_list", "item, list_name (optional)"),
                RunIntentModelAction("add_reminder", "item, day, time"),
            )),
            RunIntentModelCategory("DATE AND MATH", listOf(
                RunIntentModelAction("get_date_diff", "target_date, from_date (optional)"),
                RunIntentModelAction("calculate_arithmetic", "expression"),
                RunIntentModelAction("convert_units", "value, from_unit, to_unit"),
                RunIntentModelAction("convert_cooking_measure", "amount, from_unit, ingredient, to_unit (optional)"),
            )),
            RunIntentModelCategory("IMPORTANT DATES", listOf(
                RunIntentModelAction("save_important_date", "label, date"),
                RunIntentModelAction("list_important_dates", "no params"),
                RunIntentModelAction("remove_important_date", "label"),
            )),
            RunIntentModelCategory("NOTES", listOf(
                RunIntentModelAction("create_note", "content"),
                RunIntentModelAction("list_notes", "no params"),
            )),
        )

        internal val MODEL_CALLABLE_INTENTS =
            MODEL_ACTION_CATEGORIES.flatMap { category -> category.actions.map { it.name } }

        internal val MODEL_EXCLUDED_INTENTS = linkedMapOf(
            "get_weather" to "Dedicated top-level SDK tool; call get_weather instead.",
            "get_system_info" to "Dedicated top-level SDK tool; call get_system_info instead.",
            "save_memory" to "Dedicated top-level SDK tool; call save_memory instead.",
            "convert_currency" to "Dedicated top-level SDK tool; call convert_currency instead.",
            "smart_home_on" to "Non-callable stub until Home Assistant or Google Home integration exists.",
            "smart_home_off" to "Non-callable stub until Home Assistant or Google Home integration exists.",
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
        // Flashlight
        "Flashlight on → runIntent(intentName=\"toggle_flashlight_on\", parameters=\"{}\")",
        "Flashlight off → runIntent(intentName=\"toggle_flashlight_off\", parameters=\"{}\")",
        // Communication
        "Send email to John → runIntent(intentName=\"send_email\", parameters='{\"contact\":\"John\",\"subject\":\"Hi\",\"body\":\"Text\"}')",
        "Send SMS → runIntent(intentName=\"send_sms\", parameters='{\"contact\":\"Mom\",\"message\":\"On my way\"}')",
        "Call Dad → runIntent(intentName=\"make_call\", parameters='{\"contact\":\"Dad\"}')",
        // Scheduling
        "Set alarm 10pm → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"10pm\"}')",
        "Set alarm 9pm called dinner → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"9pm\",\"label\":\"dinner\"}')",
        "Set alarm Monday 7am → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"7am\",\"day\":\"monday\"}')",
        "Set timer 3min → runIntent(intentName=\"set_timer\", parameters='{\"duration_seconds\":\"180\"}')",
        "Cancel the pasta timer → runIntent(intentName=\"cancel_timer_named\", parameters='{\"name\":\"pasta\"}')",
        "List active timers → runIntent(intentName=\"list_timers\", parameters=\"{}\")",
        // Stopwatch
        "Check stopwatch status → runIntent(intentName=\"get_stopwatch_status\", parameters=\"{}\")",
        "Calendar event with relative date and duration → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Dinner with Sarah\",\"date\":\"next friday\",\"time\":\"19:00\",\"duration_minutes\":\"60\"}')",
        "Calendar event (explicit date) → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Lunch\",\"date\":\"2026-04-15\",\"time\":\"12:30\"}')",
        "Calendar event (relative date) → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Cancel MightyApe\",\"date\":\"next thursday\",\"time\":\"16:00\"}')",
        "Calendar with attendees → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Dinner\",\"date\":\"friday\",\"time\":\"19:00\",\"attendees\":\"Sarah,John\"}')",
        "Save important date → runIntent(intentName=\"save_important_date\", parameters='{\"label\":\"mum's birthday\",\"date\":\"15 March\"}')",
        "List important dates → runIntent(intentName=\"list_important_dates\", parameters='{}')",
        "Remove important date → runIntent(intentName=\"remove_important_date\", parameters='{\"label\":\"mum's birthday\"}')",
        "Calculator → runIntent(intentName=\"calculate_arithmetic\", parameters='{\"expression\":\"18.5% of 240\"}')",
        "Math helpers → runIntent(intentName=\"calculate_arithmetic\", parameters='{\"expression\":\"round(sqrt((25^2)) + abs(-2.6) + (10 % 3), 2)\"}')",
        "Unit conversion → runIntent(intentName=\"convert_units\", parameters='{\"value\":\"100\",\"from_unit\":\"m\",\"to_unit\":\"yards\"}')",
        "Cooking conversion → runIntent(intentName=\"convert_cooking_measure\", parameters='{\"amount\":\"3\",\"from_unit\":\"tbsp\",\"ingredient\":\"butter\",\"to_unit\":\"g\"}')",
        // System toggles
        "Turn on DND → runIntent(intentName=\"toggle_dnd_on\", parameters=\"{}\")",
        "Enable Wi-Fi → runIntent(intentName=\"toggle_wifi\", parameters='{\"state\":\"on\"}')",
        "Turn off Bluetooth → runIntent(intentName=\"toggle_bluetooth\", parameters='{\"state\":\"off\"}')",
        // Volume
        "Set volume to 50% → runIntent(intentName=\"set_volume\", parameters='{\"value\":\"50\",\"is_percent\":\"true\"}')",
        // Media
        "Play Bohemian Rhapsody → runIntent(intentName=\"play_media\", parameters='{\"query\":\"Bohemian Rhapsody\",\"artist\":\"Queen\"}')",
        "Play Abbey Road album → runIntent(intentName=\"play_media_album\", parameters='{\"album\":\"Abbey Road\",\"artist\":\"The Beatles\"}')",
        "Play workout playlist → runIntent(intentName=\"play_media_playlist\", parameters='{\"playlist\":\"Workout Mix\"}')",
        "Play cat videos on YouTube → runIntent(intentName=\"play_youtube\", parameters='{\"query\":\"cat videos\"}')",
        // Navigation
        "Navigate to airport → runIntent(intentName=\"navigate_to\", parameters='{\"destination\":\"airport\"}')",
        "Find coffee nearby → runIntent(intentName=\"find_nearby\", parameters='{\"query\":\"coffee\"}')",
        // Apps
        "Open Spotify → runIntent(intentName=\"open_app\", parameters='{\"app_name\":\"Spotify\"}')",
        // Info
        "Get battery → runIntent(intentName=\"get_battery\", parameters=\"{}\")",
        "What time is it → runIntent(intentName=\"get_time\", parameters=\"{}\")",
        // Notes
        "Create a note about milk → runIntent(intentName=\"create_note\", parameters='{\"content\":\"milk\"}')",
        "Voice memo about the meeting → runIntent(intentName=\"create_note\", parameters='{\"content\":\"about the meeting\"}')",
        "Show my notes → runIntent(intentName=\"list_notes\", parameters=\"{}\")",
        // Lists
        "Add a list called shopping → runIntent(intentName=\"create_list\", parameters='{\"list_name\":\"shopping\"}')",
        "Create a groceries list → runIntent(intentName=\"create_list\", parameters='{\"list_name\":\"groceries\"}')",
        "Add milk to my shopping list → runIntent(intentName=\"add_to_list\", parameters='{\"item\":\"milk\",\"list_name\":\"shopping\"}')",
        "Show my shopping list → runIntent(intentName=\"get_list_items\", parameters='{\"list_name\":\"shopping\"}')",
    )

    override val fullInstructions: String = buildString {
        appendLine("run_intent: Perform a native Android device action.")
        appendLine("After load_skill returns these instructions, continue the same request with the relevant run_intent action; do not call load_skill(\"run_intent\") again for that request.")
        appendLine()
        appendLine("Parameters (pass as JSON in the 'parameters' argument):")
        appendLine("- intent_name (required, string): The action to perform.")
        appendLine()
        appendLine("Available model-callable intents:")
        appendLine()
        MODEL_ACTION_CATEGORIES.forEach { category ->
            appendLine("${category.title}:")
            category.actions.forEach { action ->
                appendLine("  ${action.name} — params: ${action.parameterHelp}")
            }
            appendLine()
        }
        appendLine("Explicit exclusions:")
        MODEL_EXCLUDED_INTENTS.forEach { (intent, reason) ->
            appendLine("  $intent — $reason")
        }
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
        appendLine("'schedule [topic] on [date]', 'set up a meeting', 'block time on my calendar',")
        appendLine("'put [X] in my calendar' → runIntent with intentName=create_calendar_event.")
        appendLine("Parameters: title (required), date (use relative phrases like \"next friday\", \"tomorrow\", \"this thursday\" as-is),")
        appendLine("time (use HH:MM 24h format), duration_minutes (optional, integer number of minutes, default 60).")
        appendLine("Derive duration_minutes from the requested start and end times.")
        appendLine("Examples: 19:00\u2013\u201320:00 \u2192 duration_minutes=60 | 09:30\u2013\u201310:00 \u2192 30 | 14:00\u2013\u201315:30 \u2192 90")
        appendLine("Pass relative dates as-is. Do NOT convert relative dates like \"next friday\" to YYYY-MM-DD.")
        appendLine("Example: create_calendar_event title=\"Dinner with Sarah\" date=\"next friday\" time=19:00 duration_minutes=60")
        appendLine("CRITICAL: NEVER say 'I've put that in the diary', 'I've added it to your calendar', ")
        appendLine("or any similar confirmation without calling runIntent(create_calendar_event) first. ")
        appendLine("You MUST call the tool — do NOT confirm the event was created without the tool having been called. ")
        appendLine("If exact parameters remain unclear after reading these instructions, ask the user; do not call load_skill again.")
        appendLine("'remind me in X minutes/seconds' is set_timer, NOT create_calendar_event.")
        appendLine()
        appendLine("DND rule: 'turn on do not disturb', 'enable DND', 'silence notifications' →")
        appendLine("runIntent with intentName=toggle_dnd_on.")
        appendLine("'turn off do not disturb', 'disable DND', 'allow notifications again' →")
        appendLine("runIntent with intentName=toggle_dnd_off.")
        appendLine("If the app needs permission, it will open the settings page automatically.")
        appendLine()
        appendLine("Volume rule: 'set volume to 50%', 'volume 7' → runIntent with intentName=set_volume.")
        appendLine("If user says percentage (0-100), set is_percent=\"true\". If 1-10, set is_percent=\"false\".")
        appendLine()
        appendLine("Calculator rule: for arithmetic or calculator-style questions, ALWAYS call")
        appendLine("runIntent with intentName=calculate_arithmetic instead of doing the math yourself.")
        appendLine("Pass the math in expression exactly as symbols when already present (e.g. '245 * 17'),")
        appendLine("or as a simple phrase the evaluator understands (e.g. '18.5% of 240').")
        appendLine()
        appendLine("Unit-conversion rule: for supported deterministic physical unit conversions, call runIntent with")
        appendLine("intentName=convert_units instead of doing the conversion yourself.")
        appendLine("Pass value as a decimal string and pass explicit normalized units in from_unit/to_unit")
        appendLine("such as m, yd, km, mi, mg, g, kg, oz, lb, mL, L, tsp, tbsp, cups, gallons, celsius, fahrenheit, kelvin, m/s, mph, or km/h.")
        appendLine()
        appendLine("Cooking-conversion rule: for ingredient-aware cooking conversion questions, ALWAYS call runIntent with intentName=convert_cooking_measure.")
        appendLine("Pass amount as a decimal string, pass explicit mass or volume units in from_unit/to_unit, and include ingredient exactly as the user said it.")
        appendLine("Use this for ingredient-qualified cooking phrases like 3 tbsp butter → grams, 400 g flour → cups, 2 gallons milk → kilogrammes, or how much 2 gallons milk weighs in pounds.")
        appendLine("If an ingredient is mentioned but the request is still plain same-category physical unit math outside the cooking path — for example 3 lb flour → grams, 8 oz flour → grams, or 2 gallons milk → liters — keep using convert_units. Do NOT invent cooking densities. The native path uses a small built-in ingredient table and will fail clearly if the ingredient is unsupported.")
        appendLine()
        appendLine("Currency-conversion rule: use the dedicated top-level convert_currency tool, NOT run_intent.")
        appendLine("Pass amount as a decimal string and explicit currency codes or names in from_currency/to_currency,")
        appendLine("such as AUD, NZD, USD, EUR, or Australian dollars → New Zealand dollars.")
        appendLine("Do NOT invent or estimate exchange rates. The dedicated tool returns the latest available ECB-backed rate date and fails closed if rates are unavailable or too stale.")
        appendLine()
        appendLine("Media rule: For music playback, use play_media with query (song name) and artist.")
        appendLine("For albums, use play_media_album. For playlists, use play_media_playlist.")
        appendLine("For YouTube/Spotify/Netflix/Plex, use the corresponding intent with query parameter.")
        appendLine()
        appendLine("Navigation rule: 'navigate to X', 'directions to X' → navigate_to with destination.")
        appendLine("'find X nearby', 'where is nearest X' → find_nearby with query.")
        appendLine()
        appendLine("Call rule: 'call X', 'phone X', 'dial X' → make_call with contact name.")
        appendLine("Note rule: 'write a note', 'create a note about X', 'voice memo about X', 'add a note X', 'note to self: X', 'save a memo about X' → runIntent with intentName=create_note, parameters='{\"content\":\"X\"}'.")
        appendLine("'Show my notes', 'list my notes', 'what notes do I have', 'what notes do you have saved', 'do I have any notes', 'my notes' → runIntent with intentName=list_notes.")
        appendLine("CRITICAL: NEVER use search_memory to answer questions about notes. Notes are stored separately from memories and MUST be retrieved with list_notes. search_memory is for personal facts and preferences only.")
        appendLine("List rule: 'create a list called X', 'add a list called X', 'make a shopping list' → runIntent with intentName=create_list, parameters='{\"list_name\":\"X\"}'.")
        appendLine("'Add X to my shopping list', 'put X on the grocery list' → runIntent with intentName=add_to_list, parameters='{\"item\":\"X\",\"list_name\":\"shopping\"}'.")
        appendLine("'Show my shopping list', 'what is on my shopping list' → runIntent with intentName=get_list_items, parameters='{\"list_name\":\"shopping\"}'.")
        appendLine("Lists are stored separately from memories: never use search_memory to answer list-content requests.")
        appendLine("'Is my stopwatch running?', 'check the stopwatch status' → runIntent with intentName=get_stopwatch_status.")
        appendLine()
        appendLine("Examples:")
        examples.forEach { appendLine("  $it") }
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
