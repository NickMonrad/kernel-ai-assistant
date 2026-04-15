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
        "Perform a native Android device action. Supports flashlight, alarm, timer, calendar, email, SMS, " +
            "Do Not Disturb, volume control, system toggles (Wi-Fi, Bluetooth, airplane mode, hotspot), " +
            "media playback (local, YouTube, Spotify, Netflix, Plex), navigation, calls, app launching, and info queries. " +
            "For alarms: pass the time exactly as the user said it using the 'time' parameter (e.g. time:\"10pm\", time:\"9:30am\", time:\"22:00\"). " +
            "For calendar events, date accepts YYYY-MM-DD or relative terms like 'tomorrow', 'next wednesday'."

    override val schema = SkillSchema(
        parameters = mapOf(
            "intent_name" to SkillParameter(
                type = "string",
                description = "The action to perform.",
                enum = listOf(
                    // Flashlight
                    "toggle_flashlight_on",
                    "toggle_flashlight_off",
                    // Communication
                    "send_email",
                    "send_sms",
                    "make_call",
                    // Scheduling
                    "set_alarm",
                    "set_timer",
                    "create_calendar_event",
                    // System toggles
                    "toggle_dnd_on",
                    "toggle_dnd_off",
                    "toggle_wifi",
                    "toggle_bluetooth",
                    "toggle_airplane_mode",
                    "toggle_hotspot",
                    // Volume
                    "set_volume",
                    // Media playback
                    "play_media",
                    "play_media_album",
                    "play_media_playlist",
                    "play_youtube",
                    "play_spotify",
                    "play_netflix",
                    "play_plex",
                    // Navigation
                    "navigate_to",
                    "find_nearby",
                    // Apps
                    "open_app",
                    // Info queries
                    "get_battery",
                    "get_time",
                    "get_date",
                ),
            ),
        ),
        required = listOf("intent_name"),
    )

    override val examples: List<String> = listOf(
        // Flashlight
        "Flashlight on → runIntent(intentName=\"toggle_flashlight_on\", parameters=\"{}\")",
        "Flashlight off → runIntent(intentName=\"toggle_flashlight_off\", parameters=\"{}\")",
        // Communication
        "Send email → runIntent(intentName=\"send_email\", parameters='{\"subject\":\"Hi\",\"body\":\"Text\"}')",
        "Send SMS → runIntent(intentName=\"send_sms\", parameters='{\"contact\":\"Mom\",\"message\":\"On my way\"}')",
        "Call Dad → runIntent(intentName=\"make_call\", parameters='{\"contact\":\"Dad\"}')",
        // Scheduling
        "Set alarm 10pm → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"10pm\"}')",
        "Set alarm 9pm called dinner → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"9pm\",\"label\":\"dinner\"}')",
        "Set alarm Monday 7am → runIntent(intentName=\"set_alarm\", parameters='{\"time\":\"7am\",\"day\":\"monday\"}')",
        "Set timer 3min → runIntent(intentName=\"set_timer\", parameters='{\"duration_seconds\":\"180\"}')",
        "Calendar event → runIntent(intentName=\"create_calendar_event\", parameters='{\"title\":\"Lunch\",\"date\":\"2026-04-15\",\"time\":\"12:30\"}')",
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
    )

    override val fullInstructions: String = buildString {
        appendLine("run_intent: Perform a native Android device action.")
        appendLine()
        appendLine("Parameters (pass as JSON in the 'parameters' argument):")
        appendLine("- intent_name (required, string): The action to perform.")
        appendLine()
        appendLine("Available intents:")
        appendLine()
        appendLine("FLASHLIGHT:")
        appendLine("  toggle_flashlight_on, toggle_flashlight_off — No params")
        appendLine()
        appendLine("COMMUNICATION:")
        appendLine("  send_email — params: subject, body")
        appendLine("  send_sms — params: contact, message")
        appendLine("  make_call — params: contact")
        appendLine()
        appendLine("SCHEDULING:")
        appendLine("  set_alarm — params: time (\"10pm\", \"9:30am\"), day (optional: \"tomorrow\", \"monday\"), label (optional)")
        appendLine("  set_timer — params: duration_seconds (\"180\" for 3 min), label (optional)")
        appendLine("  create_calendar_event — params: title, date (YYYY-MM-DD or \"tomorrow\"), time (HH:MM), duration_minutes (optional), description (optional)")
        appendLine()
        appendLine("SYSTEM TOGGLES:")
        appendLine("  toggle_dnd_on, toggle_dnd_off — No params")
        appendLine("  toggle_wifi — params: state (\"on\" or \"off\")")
        appendLine("  toggle_bluetooth — params: state (\"on\" or \"off\")")
        appendLine("  toggle_airplane_mode — params: state (\"on\" or \"off\")")
        appendLine("  toggle_hotspot — params: state (\"on\" or \"off\")")
        appendLine()
        appendLine("VOLUME:")
        appendLine("  set_volume — params: value (0-100 or 1-10), is_percent (\"true\" if 0-100, \"false\" if 1-10)")
        appendLine()
        appendLine("MEDIA PLAYBACK:")
        appendLine("  play_media — params: query (song name), artist (optional)")
        appendLine("  play_media_album — params: album, artist (optional)")
        appendLine("  play_media_playlist — params: playlist")
        appendLine("  play_youtube — params: query")
        appendLine("  play_spotify — params: query")
        appendLine("  play_netflix — params: query")
        appendLine("  play_plex — params: title")
        appendLine()
        appendLine("NAVIGATION:")
        appendLine("  navigate_to — params: destination")
        appendLine("  find_nearby — params: query")
        appendLine()
        appendLine("APPS:")
        appendLine("  open_app — params: app_name")
        appendLine()
        appendLine("INFO QUERIES:")
        appendLine("  get_battery, get_time, get_date — No params")
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
        appendLine("Volume rule: 'set volume to 50%', 'volume 7' → runIntent with intentName=set_volume.")
        appendLine("If user says percentage (0-100), set is_percent=\"true\". If 1-10, set is_percent=\"false\".")
        appendLine()
        appendLine("Media rule: For music playback, use play_media with query (song name) and artist.")
        appendLine("For albums, use play_media_album. For playlists, use play_media_playlist.")
        appendLine("For YouTube/Spotify/Netflix/Plex, use the corresponding intent with query parameter.")
        appendLine()
        appendLine("Navigation rule: 'navigate to X', 'directions to X' → navigate_to with destination.")
        appendLine("'find X nearby', 'where is nearest X' → find_nearby with query.")
        appendLine()
        appendLine("Call rule: 'call X', 'phone X', 'dial X' → make_call with contact name.")
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
