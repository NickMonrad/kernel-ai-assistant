package com.kernel.ai.core.skills.natives

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

private const val TAG = "KernelAI"
private const val PHONE_PERMISSION_REQUIRED_ERROR = "Phone permission is required for auto-dial."

/**
 * Central dispatcher for all native Android operations triggered via the [run_intent][RunIntentSkill] gateway.
 *
 * Mirrors Google AI Edge Gallery's IntentHandler pattern — a single class mapping
 * [intentName] strings to concrete Android APIs or system Intents.
 *
 * Supported intents:
 *   toggle_flashlight_on    — Camera2 torch on
 *   toggle_flashlight_off   — Camera2 torch off
 *   send_email              — ACTION_SENDTO mailto: URI (params: contact, subject, body)
 *   send_sms                — ACTION_SENDTO SMS composer (params: message)
 *   set_alarm               — App-owned alarm scheduling (params: hours, minutes, label)
 *   set_timer               — App-owned timer scheduling (params: duration_seconds, label)
 *   create_calendar_event   — CalendarContract ACTION_INSERT edit screen (params: title, date, time?, duration_minutes?, description?)
 *   get_battery             — BatteryManager capacity + charging state
 *   get_time / get_date     — LocalDateTime formatted display
 *   toggle_dnd_on/off       — NotificationManager interruption filter (requests permission if needed)
 *   set_volume              — AudioManager STREAM_MUSIC (params: value, is_percent)
 *   toggle_wifi             — Opens Wi-Fi settings/panel (params: state)
 *   toggle_bluetooth        — Opens Bluetooth settings/panel (params: state)
 *   toggle_airplane_mode    — Opens Airplane mode settings
 *   toggle_hotspot          — Opens wireless settings
 *   play_media              — INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH (params: query, artist?)
 *   play_media_album        — INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH album focus (params: album, artist?)
 *   play_media_playlist     — INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH playlist focus (params: playlist)
 *   pause_media             — AudioManager KEYCODE_MEDIA_PAUSE
 *   stop_media              — AudioManager KEYCODE_MEDIA_STOP
 *   next_track              — AudioManager KEYCODE_MEDIA_NEXT
 *   previous_track          — AudioManager KEYCODE_MEDIA_PREVIOUS
 *   play_podcast            — Browser search for podcast (stub) (params: show)
 *   podcast_skip_forward    — AudioManager KEYCODE_MEDIA_FAST_FORWARD (params: seconds)
 *   podcast_skip_back       — AudioManager KEYCODE_MEDIA_REWIND (params: seconds)
 *   podcast_speed           — DirectReply (app-level support required) (params: rate)
 *   play_plex               — Plex deep link (params: title)
 *   navigate_to             — Google Maps / geo: URI (params: destination)
 *   find_nearby             — geo: URI nearby search (params: query)
 *   make_call               — ACTION_DIAL with contact resolution (params: contact)
 *   add_to_list             — Insert item into list (params: item, list_name) — returns DirectReply
 *   bulk_add_to_list        — Insert multiple items at once (params: items (CSV or JSON array), list_name?) — returns DirectReply
 *   create_list             — Create a named list (params: list_name)
 *   get_list_items          — Retrieve unchecked items from a list (params: list_name?)
 *   remove_from_list        — Remove an item from a list (params: item, list_name?)
 *   smart_home_on/off       — Stub pending HA/Google Home (#311/#312) (params: device)
 *   cancel_alarm            — AlarmClock.ACTION_DISMISS_ALARM (params: label?)
 *   get_weather             — Opens Google search for weather (params: location?)
 *   get_system_info         — Returns storage and RAM info — returns DirectReply
 *   get_date_diff           — Native date arithmetic: days/weeks until or since a date (params: target_date, from_date?) — returns DirectReply
 *   save_memory             — Saves content to long-term core memory (params: content)
 *   set_brightness          — Sets screen brightness (params: value?, direction?, is_percent?)
 */
@Singleton
class NativeIntentHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clockRepository: ClockRepository,
    private val listItemDao: ListItemDao,
    private val listNameDao: ListNameDao,
    private val contactAliasRepository: ContactAliasRepository,
    private val memoryRepository: MemoryRepository,
    private val embeddingEngine: EmbeddingEngine,
) {

    fun handle(intentName: String, params: Map<String, String>): SkillResult {
        val normalizedName = normalizeIntentName(intentName)
        if (normalizedName != intentName) {
            Log.w(TAG, "NativeIntentHandler: normalized intent '$intentName' -> '$normalizedName'")
        }
        Log.d(TAG, "NativeIntentHandler.handle: intent=$normalizedName params=$params")
        return try {
            when (normalizedName) {
                "toggle_flashlight_on" -> setTorch(true)
                "toggle_flashlight_off" -> setTorch(false)
                "send_email" -> sendEmail(params)
                "send_sms" -> sendSms(params)
                "set_alarm" -> setAlarm(params)
                "set_timer" -> setTimer(params)
                "cancel_timer" -> cancelTimer()
                "list_timers" -> listTimers()
                "cancel_timer_named" -> cancelTimerNamed(params)
                "get_timer_remaining" -> getTimerRemaining(params)
                "cancel_alarm" -> cancelAlarm(params)
                "create_calendar_event" -> createCalendarEvent(params)
                "get_battery" -> getBattery()
                "get_time", "get_date" -> getTime(params)
                "toggle_dnd_on" -> setDoNotDisturb(true)
                "toggle_dnd_off" -> setDoNotDisturb(false)
                "set_volume" -> setVolume(params)
                "toggle_wifi" -> openWifiSettings(params["state"] ?: "on")
                "toggle_bluetooth" -> openBluetoothSettings(params["state"] ?: "on")
                "toggle_airplane_mode" -> openAirplaneModeSettings()
                "toggle_hotspot" -> openHotspotSettings()
                "play_media", "play_media_album", "play_media_playlist" -> playMedia(params)
                "play_youtube" -> playYoutube(params)
                "play_spotify" -> playSpotify(params)
                "play_plexamp" -> playPlexamp(params)
                "play_youtube_music" -> playYoutubeMusic(params)
                "play_netflix" -> playNetflix(params)
                "play_plex" -> playPlex(params)
                "play_podcast" -> playPodcast(params)
                "podcast_skip_forward" -> podcastSkip(params, forward = true)
                "podcast_skip_back" -> podcastSkip(params, forward = false)
                "podcast_speed" -> setPodcastSpeed(params)
                "pause_media" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                "stop_media" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
                "next_track" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous_track" -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "open_app" -> openApp(params)
                "navigate_to" -> navigateTo(params)
                "find_nearby" -> findNearby(params)
                "make_call" -> makeCall(params)
                "add_to_list" -> addToList(params)
                "bulk_add_to_list" -> bulkAddToList(params)
                "create_list" -> createList(params)
                "get_list_items" -> getListItems(params)
                "remove_from_list" -> removeFromList(params)
                "smart_home_on" -> handleSmartHome(params["device"] ?: "device", true)
                "smart_home_off" -> handleSmartHome(params["device"] ?: "device", false)
                "get_weather" -> getWeather(params)
                "get_date_diff" -> getDateDiff(params)
                "get_system_info" -> getSystemInfo()
                "save_memory" -> saveMemory(params)
                "set_brightness" -> setBrightness(params)
                else -> SkillResult.Failure("run_intent", "Unknown intent: $intentName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NativeIntentHandler.handle($normalizedName) failed", e)
            SkillResult.Failure("run_intent", e.message ?: "Unknown error")
        }
    }

    /**
     * Normalizes LLM-generated intent names to their canonical form.
     *
     * Small models occasionally omit underscores (e.g. "toggle_flashlightoff" instead of
     * "toggle_flashlight_off") or add spaces. This function:
     *   1. Trims and lowercases the input
     *   2. Returns immediately on exact match
     *   3. Strips all underscores/spaces and compares against the same normalization of every
     *      known intent name — handles any underscore insertion or omission
     */
    private fun normalizeIntentName(raw: String): String {
        val trimmed = raw.trim().lowercase()
        INTENT_ALIASES[trimmed]?.let { return it }
        if (trimmed in KNOWN_INTENTS) return trimmed
        // Strip all word separators and compare canonically
        val stripped = trimmed.replace(Regex("[_\\s]+"), "")
        return KNOWN_INTENTS.firstOrNull { it.replace("_", "") == stripped } ?: trimmed
    }

    companion object {
        private val INTENT_ALIASES = mapOf(
            "get_list" to "get_list_items",
        )

        private val KNOWN_INTENTS = setOf(
            "toggle_flashlight_on", "toggle_flashlight_off",
            "send_email", "send_sms", "make_call",
            "set_alarm", "cancel_alarm",
            "set_timer", "cancel_timer", "cancel_timer_named", "list_timers", "get_timer_remaining",
            "create_calendar_event",
            "get_battery", "get_time", "get_date",
            "toggle_dnd_on", "toggle_dnd_off",
            "set_volume", "set_brightness",
            "toggle_wifi", "toggle_bluetooth", "toggle_airplane_mode", "toggle_hotspot",
            "play_media", "play_media_album", "play_media_playlist",
            "play_youtube", "play_spotify", "play_plexamp", "play_youtube_music",
            "play_netflix", "play_plex", "play_podcast",
            "pause_media", "stop_media", "next_track", "previous_track",
            "podcast_skip_forward", "podcast_skip_back", "podcast_speed",
            "open_app", "navigate_to", "find_nearby",
            "add_to_list", "bulk_add_to_list", "create_list", "get_list_items", "remove_from_list",
            "smart_home_on", "smart_home_off",
            "get_weather", "get_date_diff", "get_system_info",
            "save_memory",
        )

        private val GENERIC_MEDIA_QUERY_KEYS = setOf(
            "music",
            "somemusic",
            "songs",
            "asong",
            "some songs".replace(" ", ""),
        )
    }



    private fun setTorch(enabled: Boolean): SkillResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return SkillResult.Failure("run_intent", "No flash unit found on this device.")
        cameraManager.setTorchMode(cameraId, enabled)
        val state = if (enabled) "on" else "off"
        Log.d(TAG, "NativeIntentHandler: torch=$state")
        return SkillResult.Success("Flashlight turned $state.")
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private fun sendEmail(params: Map<String, String>): SkillResult {
        // Use ACTION_SENDTO with mailto: URI so the system routes directly to an
        // email app instead of showing the generic share sheet (ACTION_SEND behaviour).
        val contact = params["contact"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("send_email", "No contact specified")
        val subject = params["subject"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("send_email", "No subject specified")
        val body = params["body"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("send_email", "No body specified")
        val resolvedEmail = resolveContactEmail(contact)
            ?: contact.takeIf(::looksLikeEmailAddress)

        val encodedSubject = Uri.encode(subject)
        val encodedBody = Uri.encode(body)
        // Email address must NOT be percent-encoded (RFC 6068) — only query params are encoded
        val mailtoUri = resolvedEmail?.let {
            Uri.parse("mailto:$it?subject=$encodedSubject&body=$encodedBody")
        } ?: Uri.parse("mailto:?subject=$encodedSubject&body=$encodedBody")
        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            if (resolvedEmail != null) {
                SkillResult.Success("Email composer opened to $resolvedEmail.")
            } else {
                SkillResult.Success("Email composer opened. Couldn't prefill recipient for $contact.")
            }
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("send_email", "No email app available")
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private fun sendSms(params: Map<String, String>): SkillResult {
        val contact = params["contact"] ?: params["phone"]
        // resolveContactNumber returns null for self-terms when own number unavailable;
        // fall back to blank URI rather than passing the literal word through as a number.
        val number = contact?.let { resolveContactNumber(it) }
            ?: contact?.takeIf { it.none { c -> c.isLetter() } }  // keep raw numeric strings
        val smsUri = if (!number.isNullOrBlank()) "smsto:${Uri.encode(number)}" else "smsto:"
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(smsUri)
            putExtra("sms_body", params["message"] ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            SkillResult.Success("SMS composer opened${contact?.let { " for $it" } ?: ""}.")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("send_sms", "No SMS app available")
        }
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private fun setAlarm(params: Map<String, String>): SkillResult {
        val timeStr = params["time"]
            ?: return SkillResult.Failure("run_intent", "No time specified for alarm.")
        val resolvedTime = resolveTime(timeStr)
            ?: return SkillResult.Failure("run_intent", "Couldn't parse time: $timeStr")

        val label = params["label"]?.takeIf { it.isNotBlank() }
        val day = params["day"]?.trim()?.takeIf { it.isNotBlank() }
        val resolvedDate = day?.let { resolveDate(it) }
        if (day != null && resolvedDate == null) {
            return SkillResult.Failure("run_intent", "Couldn't parse day: $day")
        }

        val triggerAt = if (resolvedDate != null) {
            resolvedDate
                .atTime(resolvedTime)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } else {
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
            val candidate = now.toLocalDate().atTime(resolvedTime).atZone(zone)
            if (candidate.toInstant().toEpochMilli() > System.currentTimeMillis()) {
                candidate.toInstant().toEpochMilli()
            } else {
                candidate.plusDays(1).toInstant().toEpochMilli()
            }
        }

        if (triggerAt <= System.currentTimeMillis()) {
            return SkillResult.Failure("run_intent", "That time has already passed.")
        }

        val scheduled = runBlocking {
            clockRepository.scheduleAlarm(triggerAt, label)
        }
        if (scheduled != null) {
            val formatter = DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
                .withZone(ZoneId.systemDefault())
            val formattedTime = formatter.format(Instant.ofEpochMilli(scheduled.triggerAtMillis))
            return SkillResult.Success(
                "Alarm set for $formattedTime${if (label != null) " — $label" else ""}"
            )
        }
        if (clockRepository.getPlatformState().canScheduleExactAlarms) {
            return SkillResult.Failure("run_intent", "Could not schedule the alarm.")
        }

        val (hours, minutes) = resolvedTime.hour to resolvedTime.minute
        val dayDisplay = day?.replaceFirstChar { it.uppercase() }
        val messageLabel = when {
            dayDisplay != null && label != null -> "$dayDisplay: $label"
            dayDisplay != null -> dayDisplay
            else -> label
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hours)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            messageLabel?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            val dayLabel = dayDisplay?.let { " for $it" } ?: ""
            val dayWarning = dayDisplay?.let {
                " Exact alarms are unavailable, so please verify the date is set to $it before confirming."
            } ?: ""
            SkillResult.Success(
                "Clock app opened — alarm$dayLabel at %02d:%02d.$dayWarning".format(hours, minutes)
            )
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("run_intent", "No clock app found to set an alarm.")
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun setTimer(params: Map<String, String>): SkillResult {
        val seconds = params["duration_seconds"]?.toIntOrNull()
            ?: return SkillResult.Failure("run_intent", "duration_seconds is required and must be an integer.")
        val durationMs = seconds * 1000L
        val label = params["label"]?.takeIf { it.isNotBlank() }
        val scheduled = runBlocking {
            clockRepository.scheduleTimer(durationMs, label)
        } ?: return if (!clockRepository.getPlatformState().canScheduleExactAlarms) {
            SkillResult.Failure("run_intent", "Exact alarms are unavailable right now.")
        } else {
            SkillResult.Failure("run_intent", "Could not schedule the timer.")
        }
        val mins = (scheduled.durationMs / 1000) / 60
        val secs = (scheduled.durationMs / 1000) % 60
        val labelStr = when {
            mins > 0 && secs > 0 -> "$mins min $secs sec"
            mins > 0 -> "$mins minute${if (mins != 1L) "s" else ""}"
            else -> "${scheduled.durationMs / 1000} seconds"
        }
        return SkillResult.Success("Timer set for $labelStr.")
    }

    private fun cancelTimer(): SkillResult {
        val cancelled = runBlocking { clockRepository.cancelAllTimers() }
        if (cancelled == 0) return SkillResult.DirectReply("No timers running.")
        return SkillResult.Success("Cancelled $cancelled timer${if (cancelled == 1) "" else "s"}.")
    }

    // ── Timer Registry ────────────────────────────────────────────────────────

    private fun listTimers(): SkillResult {
        val timers = runBlocking { clockRepository.getAllTimers() }
        if (timers.isEmpty()) return SkillResult.DirectReply("No timers running.")
        val now = System.currentTimeMillis()
        val lines = timers.mapIndexed { i, t ->
            val label = t.label ?: "Timer ${i + 1}"
            val remMs = (t.startedAtMillis + t.durationMs) - now
            val remaining = if (remMs > 0) formatDuration(remMs / 1000) else "finished"
            "• $label — $remaining remaining"
        }
        return SkillResult.DirectReply(lines.joinToString("\n"))
    }

    private fun cancelTimerNamed(params: Map<String, String>): SkillResult {
        val name = params["name"] ?: return cancelTimer()
        val durationMs = parseDurationToMs(name)
        val cancelled = runBlocking { clockRepository.cancelTimersMatching(name, durationMs) }
        if (cancelled == 0) return SkillResult.Failure("cancel_timer_named", "No timer named '$name' found")
        return SkillResult.Success(
            if (cancelled == 1) "Cancelled the $name timer"
            else "Cancelled $cancelled timers matching $name"
        )
    }

    private fun getTimerRemaining(params: Map<String, String>): SkillResult {
        val name = params["name"]
        val timers = runBlocking { clockRepository.getAllTimers() }
        if (timers.isEmpty()) return SkillResult.DirectReply("No timers running.")
        val timer = if (name != null) {
            timers.firstOrNull { it.label?.contains(name, ignoreCase = true) == true }
                ?: timers.first()
        } else timers.first()
        val now = System.currentTimeMillis()
        val remMs = (timer.startedAtMillis + timer.durationMs) - now
        return if (remMs > 0) {
            val label = timer.label ?: "Timer"
            SkillResult.DirectReply("$label — ${formatSpokenDuration(remMs / 1000)} remaining")
        } else {
            SkillResult.DirectReply("Timer has finished.")
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
            hrs > 0 -> "${hrs} hour${if (hrs != 1L) "s" else ""}"
            mins > 0 && secs > 0 -> "${mins}m ${secs}s"
            mins > 0 -> "${mins} minute${if (mins != 1L) "s" else ""}"
            else -> "${secs} seconds"
        }
    }

    private fun formatSpokenDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        val parts = buildList {
            if (hrs > 0) add("$hrs hour${if (hrs != 1L) "s" else ""}")
            if (mins > 0) add("$mins minute${if (mins != 1L) "s" else ""}")
            if (secs > 0 || isEmpty()) add("$secs second${if (secs != 1L) "s" else ""}")
        }
        return parts.joinToString(" ")
    }

    private fun parseDurationToMs(text: String): Long? {
        val match = Regex("""(\d+)\s*(minute|min|hour|hr|second|sec)""", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "hour", "hr" -> amount * 3_600_000L
            "minute", "min" -> amount * 60_000L
            else -> amount * 1_000L
        }
    }

    // ── Calendar ──────────────────────────────────────────────────────────────

    private fun createCalendarEvent(params: Map<String, String>): SkillResult {
        val title = params["title"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("run_intent", "title is required for create_calendar_event.")
        val dateStr = params["date"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("run_intent", "date is required (YYYY-MM-DD) for create_calendar_event.")

        val date = resolveDate(dateStr)
            ?: return SkillResult.Failure(
                "run_intent",
                "Could not parse date '$dateStr' — use YYYY-MM-DD or a relative term like 'next wednesday'.",
            )

        val timeStr = params["time"]?.takeIf { it.isNotBlank() }
        val durationMinutes = params["duration_minutes"]?.toIntOrNull() ?: 60
        if (durationMinutes <= 0) {
            return SkillResult.Failure("run_intent", "duration_minutes must be greater than 0 (received: $durationMinutes).")
        }
        val zone = ZoneId.systemDefault()

        var resolvedTimeLabel: String? = null
        val (beginMillis, endMillis) = if (timeStr != null) {
            val time = resolveTime(timeStr)
                ?: return SkillResult.Failure("run_intent", "Invalid time format '$timeStr' — expected HH:MM (24h).")
            resolvedTimeLabel = time.format(DateTimeFormatter.ofPattern("HH:mm"))
            val begin = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            val end = begin + durationMinutes * 60_000L
            begin to end
        } else {
            // All-day event: pass midnight-to-midnight in UTC as CalendarContract expects
            val begin = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            val end = begin + 24 * 60 * 60_000L
            begin to end
        }

        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            if (timeStr == null) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            params["description"]?.takeIf { it.isNotBlank() }?.let {
                putExtra(CalendarContract.Events.DESCRIPTION, it)
            }
            // Attendees: resolve each name to email from contacts; skip unresolvable names
            val attendeeEmails = params["attendees"]
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf { n -> n.isNotBlank() }?.let { n -> resolveContactEmail(n) } }
                ?.joinToString(",")
                ?.takeIf { it.isNotBlank() }
            if (attendeeEmails != null) putExtra(Intent.EXTRA_EMAIL, attendeeEmails)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return SkillResult.Failure("run_intent", "No calendar app found on this device.")
        }
        return try {
            context.startActivity(intent)
            val timeLabel = if (resolvedTimeLabel != null) " at $resolvedTimeLabel" else " (all day)"
            val resolvedDateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val attendeesLabel = params["attendees"]?.takeIf { it.isNotBlank() }?.let { " with $it" } ?: ""
            SkillResult.Success("Calendar opened — '${title}' on $resolvedDateStr$timeLabel$attendeesLabel. Please review and save in your calendar app.")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("run_intent", "No calendar app found on this device.")
        }
    }

    // ── Battery ───────────────────────────────────────────────────────────────

    private fun getBattery(): SkillResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val chargingSuffix = if (charging) " and charging" else ""
        // DirectReply: numeric sensor data — LLM rephrasing risks corrupting the percentage value.
        return SkillResult.DirectReply("Battery is at $pct%$chargingSuffix")
    }

    // ── Time / Date ───────────────────────────────────────────────────────────

    private fun getTime(params: Map<String, String> = emptyMap()): SkillResult {
        val now = LocalDateTime.now()
        // DirectReply: factual time/date data — LLM wrapping risks corrupting values
        // (e.g. "3:47 PM" → "nearly four o'clock") and adds no value for a simple query.
        return when (params["query_type"]) {
            "time" -> SkillResult.DirectReply(
                "It's ${now.format(DateTimeFormatter.ofPattern("h:mm a"))}",
            )
            "date" -> SkillResult.DirectReply(
                "Today is ${now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))}",
            )
            "day_of_week" -> SkillResult.DirectReply(
                "It's ${now.format(DateTimeFormatter.ofPattern("EEEE"))}",
            )
            "year" -> SkillResult.DirectReply("It's ${now.year}")
            "month" -> SkillResult.DirectReply(
                "It's ${now.format(DateTimeFormatter.ofPattern("MMMM"))}",
            )
            "week" -> {
                val week = now.get(WeekFields.ISO.weekOfWeekBasedYear())
                SkillResult.DirectReply("It's week $week of ${now.year}")
            }
            else -> {
                val time = now.format(DateTimeFormatter.ofPattern("h:mm a"))
                val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
                SkillResult.DirectReply("It's $time on $date")
            }
        }
    }

    // ── Do Not Disturb ────────────────────────────────────────────────────────

    private fun setDoNotDisturb(enabled: Boolean): SkillResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return SkillResult.Success("Please grant Do Not Disturb access in settings")
        }
        val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
                     else NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
        return SkillResult.Success(if (enabled) "Do Not Disturb is on" else "Do Not Disturb is off")
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    private fun setVolume(params: Map<String, String>): SkillResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val direction = params["direction"]
        val raw = params["value"]?.toIntOrNull()
        val isPercent = params["is_percent"] == "true"

        val targetVol = when {
            direction == "up" -> (currentVol + (maxVol * 0.15).toInt()).coerceAtMost(maxVol)
            direction == "down" -> (currentVol - (maxVol * 0.15).toInt()).coerceAtLeast(0)
            direction == "max" -> maxVol
            direction == "min" || direction == "mute" -> 0
            raw != null && isPercent -> (raw.coerceIn(0, 100) * maxVol / 100.0).roundToInt()
            raw != null -> ((raw.coerceIn(1, 10) - 1) * maxVol / 9.0).roundToInt()
            else -> return SkillResult.Failure("set_volume", "No volume value or direction provided")
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
        val pct = (targetVol * 100 / maxVol)
        return SkillResult.Success(when {
            direction == "mute" || targetVol == 0 -> "Volume muted"
            direction != null -> "Volume ${if (direction == "up") "increased" else "decreased"} to $pct%"
            else -> "Volume set to $pct%"
        })
    }

    // ── Wi-Fi ─────────────────────────────────────────────────────────────────

    private fun openWifiSettings(state: String): SkillResult {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return SkillResult.Success("Opening Wi-Fi settings")
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    private fun openBluetoothSettings(state: String): SkillResult {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return SkillResult.Success("Opening Bluetooth settings")
    }

    // ── Airplane Mode ─────────────────────────────────────────────────────────

    private fun openAirplaneModeSettings(): SkillResult {
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return SkillResult.Success("Opening Airplane mode settings")
    }

    // ── Hotspot ───────────────────────────────────────────────────────────────

    private fun openHotspotSettings(): SkillResult {
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return SkillResult.Success("Opening hotspot settings")
    }

    // ── Media Playback ────────────────────────────────────────────────────────

    private fun playMedia(params: Map<String, String>): SkillResult {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            when {
                params.containsKey("album") -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio_albums")
                    putExtra(MediaStore.EXTRA_MEDIA_ALBUM, params["album"])
                    params["artist"]?.let { putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                }
                params.containsKey("playlist") -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                    putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, params["playlist"])
                }
                else -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                    putExtra(SearchManager.QUERY, params["query"] ?: "")
                    params["artist"]?.let { putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                }
            }
        }
        return try {
            context.startActivity(intent)
            SkillResult.Success("Playing ${params["query"] ?: params["album"] ?: params["playlist"] ?: "media"}")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("play_media", "No music app found to handle this request")
        }
    }

    // ── Media Transport Controls ──────────────────────────────────────────

    private fun dispatchMediaKey(keyCode: Int): SkillResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
        val label = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Started playback"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused"
            KeyEvent.KEYCODE_MEDIA_STOP -> "Stopped"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Skipped to next track"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous track"
            else -> "Done"
        }
        return SkillResult.Success(label)
    }

    // ── Plex ──────────────────────────────────────────────────────────────────

    private fun playPlex(params: Map<String, String>): SkillResult {
        val title = params["title"] ?: return SkillResult.Failure("play_plex", "No title provided")
        return try {
            // plex:// deep links require a server-specific metadataKey we don't have at runtime.
            // Use ACTION_SEARCH with the Plex package as the best available fallback until the
            // full Plex API integration is implemented (see GitHub issue for native playback).
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.plexapp.android"
                putExtra(SearchManager.QUERY, title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching Plex for: $title")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("play_plex", "Plex app not installed")
        }
    }

    // ── YouTube ──

    private fun playYoutube(params: Map<String, String>): SkillResult {
        val query = params["query"] ?: return SkillResult.Failure("play_youtube", "No search query provided")
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.google.android.youtube"
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching YouTube for: $query")
        } catch (e: ActivityNotFoundException) {
            // Fall back to browser
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            SkillResult.Success("Opening YouTube in browser for: $query")
        }
    }

    // ── Spotify ──

    private fun playSpotify(params: Map<String, String>): SkillResult {
        val query = params["query"] ?: return SkillResult.Failure("play_spotify", "No search query provided")
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("spotify:search:${Uri.encode(query)}")).apply {
                `package` = "com.spotify.music"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching Spotify for: $query")
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            SkillResult.Success("Opening Spotify in browser for: $query")
        }
    }

    // ── Plexamp ──

    private fun playPlexamp(params: Map<String, String>): SkillResult {
        val query = normalizeMediaAppQuery(params["query"])
        val launchIntent = findLaunchIntent(
            appName = "plexamp",
            preferredPackage = "tv.plex.labs.plexamp",
        )
        return if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            if (query != null) {
                SkillResult.Success("Opening Plexamp for: $query")
            } else {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                SkillResult.Success("Opening Plexamp and starting playback")
            }
        } else {
            SkillResult.Failure("play_plexamp", "Plexamp app not installed")
        }
    }

    // ── YouTube Music ──

    private fun playYoutubeMusic(params: Map<String, String>): SkillResult {
        val query = normalizeMediaAppQuery(params["query"])
        if (query == null) {
            val launchIntent = findLaunchIntent(
                appName = "youtube music",
                preferredPackage = "com.google.android.apps.youtube.music",
            )
            return if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                SkillResult.Success("Opening YouTube Music and starting playback")
            } else {
                SkillResult.Failure("play_youtube_music", "YouTube Music app not installed")
            }
        }
        return try {
            // ACTION_VIEW with a music.youtube.com URL navigates directly to search results
            // in the app, which is a better UX than ACTION_SEARCH (which only opens the search bar).
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).apply {
                `package` = "com.google.android.apps.youtube.music"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching YouTube Music for: $query")
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            SkillResult.Success("Opening YouTube Music in browser for: $query")
        }
    }

    // ── Netflix ──

    private fun playNetflix(params: Map<String, String>): SkillResult {
        val query = params["query"] ?: return SkillResult.Failure("play_netflix", "No title provided")
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.netflix.mediaclient"
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching Netflix for: $query")
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.netflix.com/search?q=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            SkillResult.Success("Opening Netflix in browser for: $query")
        }
    }

    // ── Podcast Controls ──────────────────────────────────────────────────

    private fun playPodcast(params: Map<String, String>): SkillResult {
        val show = params["show"]
        // Try to open a podcast app or fall back to search
        return try {
            val query = if (show != null) "$show podcast" else "podcast"
            val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching for${if (show != null) " $show" else ""} podcast")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("play_podcast", "No app available to play podcasts")
        }
    }

    private fun podcastSkip(params: Map<String, String>, forward: Boolean): SkillResult {
        val seconds = params["seconds"]?.toIntOrNull() ?: if (forward) 30 else 15
        // Dispatch media key — KEYCODE_MEDIA_FAST_FORWARD / REWIND
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        val dir = if (forward) "forward" else "back"
        return SkillResult.Success("Skipped $dir ${seconds}s")
    }

    private fun setPodcastSpeed(params: Map<String, String>): SkillResult {
        val rate = params["rate"]?.toFloatOrNull() ?: 1.0f
        // Playback speed requires app-level support — return DirectReply to inform user
        return SkillResult.DirectReply("Playback speed control requires support from the active podcast app. Current request: ${rate}x")
    }

    // ── Open App ──

    private fun openApp(params: Map<String, String>): SkillResult {
        val appName = params["app_name"] ?: return SkillResult.Failure("open_app", "No app name provided")
        val launchIntent = findLaunchIntent(appName)
        return if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            SkillResult.Success("Opening $appName")
        } else {
            SkillResult.Failure("open_app", "Could not find app: $appName")
        }
    }

    private fun findLaunchIntent(appName: String, preferredPackage: String? = null): Intent? {
        val pm = context.packageManager
        preferredPackage?.let { preferred ->
            pm.getLaunchIntentForPackage(preferred)?.let { return it }
        }
        val requestedKey = normalizeAppLookupKey(appName)
        val matchingApp = pm.getInstalledApplications(0).firstOrNull { appInfo ->
            val labelKey = normalizeAppLookupKey(pm.getApplicationLabel(appInfo).toString())
            val packageKey = normalizeAppLookupKey(appInfo.packageName)
            labelKey == requestedKey || packageKey.endsWith(requestedKey)
        } ?: pm.getInstalledApplications(0).firstOrNull { appInfo ->
            val labelKey = normalizeAppLookupKey(pm.getApplicationLabel(appInfo).toString())
            val packageKey = normalizeAppLookupKey(appInfo.packageName)
            labelKey.contains(requestedKey) || packageKey.contains(requestedKey)
        }
        return matchingApp?.let { pm.getLaunchIntentForPackage(it.packageName) }
    }

    private fun normalizeAppLookupKey(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
    }

    private fun normalizeMediaAppQuery(raw: String?): String? {
        val query = raw?.trim()?.trimEnd('.', ',', '!', '?')?.takeIf { it.isNotBlank() } ?: return null
        val normalizedKey = normalizeAppLookupKey(query)
        return if (normalizedKey in GENERIC_MEDIA_QUERY_KEYS) null else query
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateTo(params: Map<String, String>): SkillResult {
        val destination = params["destination"]
            ?: return SkillResult.Failure("navigate_to", "No destination provided")
        return try {
            val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(navIntent)
            SkillResult.Success("Navigating to $destination")
        } catch (e: ActivityNotFoundException) {
            // Fall back to generic geo intent (works with any maps app)
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(geoIntent)
            SkillResult.Success("Opening maps for $destination")
        }
    }

    // ── Find Nearby ───────────────────────────────────────────────────────────

    private fun findNearby(params: Map<String, String>): SkillResult {
        val query = params["query"] ?: return SkillResult.Failure("find_nearby", "No search query provided")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching for $query nearby")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("find_nearby", "No maps app available")
        }
    }

    // ── Phone Call ────────────────────────────────────────────────────────────

    private fun makeCall(params: Map<String, String>): SkillResult {
        val contact = params["contact"] ?: return SkillResult.Failure("make_call", "No contact specified")
        if (isVoicemailTarget(contact)) {
            return openVoicemail(contact)
        }

        // If the input looks like a phone number (digits, +, spaces, dashes), dial it directly.
        val looksLikeNumber = contact.replace(Regex("[\\s\\-().+]"), "").all { it.isDigit() } &&
            contact.replace(Regex("[\\s\\-().+]"), "").isNotEmpty()
        val phoneNumber = resolveContactNumber(contact)
            ?: if (looksLikeNumber) contact
            else return SkillResult.Failure(
                "make_call",
                "Couldn't find a contact for '$contact'. You can add them in Settings → People & Contacts.",
            )
        return try {
            // Use ACTION_CALL to auto-dial (hands-free use case — e.g. driving).
            val canCall = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!canCall) {
                return SkillResult.Failure("make_call", PHONE_PERMISSION_REQUIRED_ERROR)
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Calling $contact")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("make_call", "No phone app available")
        }
    }

    private fun openVoicemail(label: String): SkillResult {
        return try {
            val canCall = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!canCall) {
                return SkillResult.Failure("make_call", PHONE_PERMISSION_REQUIRED_ERROR)
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("voicemail:")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Calling $label")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("make_call", "No phone app available")
        }
    }

    private fun isVoicemailTarget(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
        return normalized == "voicemail"
    }

    private data class ContactPhoneCandidate(
        val contactId: String,
        val displayName: String,
        val phoneNumber: String,
        val isPrimary: Boolean,
        val isSuperPrimary: Boolean,
        val phoneType: Int,
    )

    private fun resolveContactNumber(name: String): String? {
        // 0. Self-referential aliases — resolve to device's own phone number
        val selfTerms = setOf("myself", "me", "my number", "my phone", "my cell")
        val normalised0 = name.trim().lowercase()
        if (normalised0 in selfTerms) {
            val ownNumber = try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.line1Number?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not retrieve own phone number", e)
                null
            }
            Log.d(TAG, "Self-referential contact '$name' → own number: $ownNumber")
            return ownNumber  // null means SMS composer opens without pre-filled number
        }

        // 1. Alias check — strip common prefixes, normalise, look up in DB
        val normalised = name.trim().lowercase()
            .removePrefix("my ").removePrefix("the ")
            .trim()
        val aliasMatch = runBlocking { contactAliasRepository.getByAlias(normalised) }
        if (aliasMatch != null) {
            Log.d(TAG, "Alias resolved: '$name' → '${aliasMatch.displayName}' (${aliasMatch.phoneNumber})")
            return aliasMatch.phoneNumber
        }

        val requestedKey = normalizeContactLookupKey(name)
        val requestedTokens = tokenizeContactLookupTerms(name)

        // 2. ContactsContract search — tokenize the request so direct contact names still
        // resolve even when spacing/punctuation differ, and only auto-select when the best
        // match points to a single contact.
        return try {
            val exactMatches = queryContactPhoneCandidates(
                selection = requestedTokens.joinToString(" AND ") {
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                },
                selectionArgs = requestedTokens.map { "%$it%" }.toTypedArray(),
            )
            val matches = if (exactMatches.isNotEmpty() || requestedTokens.size <= 1) {
                exactMatches
            } else {
                queryContactPhoneCandidates(
                    selection = requestedTokens.joinToString(" OR ") {
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                    },
                    selectionArgs = requestedTokens.map { "%$it%" }.toTypedArray(),
                )
            }
            val rankedMatches = matches
                .distinctBy { "${it.contactId}:${it.phoneNumber}" }
                .mapNotNull { candidate ->
                    scoreContactPhoneCandidate(
                        requestedKey = requestedKey,
                        requestedTokens = requestedTokens,
                        candidate = candidate,
                    )?.let { score -> candidate to score }
                }
            when {
                rankedMatches.isEmpty() -> {
                    Log.d(TAG, "Contact not found for '$name'")
                    null
                }
                else -> {
                    val bestScore = rankedMatches.maxOf { it.second }
                    val topMatches = rankedMatches.filter { it.second == bestScore }
                    val topContactIds = topMatches
                        .map { it.first.contactId }
                        .toSet()
                    if (topContactIds.size != 1) {
                        Log.d(TAG, "Multiple contacts match '$name' at top score — not pre-populating")
                        null
                    } else {
                        val selected = topMatches
                            .map { it.first }
                            .sortedWith(
                                compareByDescending<ContactPhoneCandidate> { it.isSuperPrimary }
                                    .thenByDescending { it.isPrimary }
                                    .thenByDescending {
                                        it.phoneType == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                    }
                            )
                            .first()
                        Log.d(TAG, "Contact resolved: '$name' → ${selected.phoneNumber}")
                        selected.phoneNumber
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted — cannot resolve '$name'", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for '$name'", e)
            null
        }
    }

    private fun queryContactPhoneCandidates(
        selection: String,
        selectionArgs: Array<String>,
    ): List<ContactPhoneCandidate> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        return context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val matches = mutableListOf<ContactPhoneCandidate>()
            while (cursor.moveToNext()) {
                val phoneNumber = cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                )
                if (phoneNumber.isNullOrBlank()) continue
                matches += ContactPhoneCandidate(
                    contactId = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    ) ?: "",
                    displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    ) ?: "",
                    phoneNumber = phoneNumber,
                    isPrimary = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
                    ) == 1,
                    isSuperPrimary = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
                    ) == 1,
                    phoneType = cursor.getInt(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    ),
                )
            }
            matches
        }.orEmpty()
    }

    private fun normalizeContactLookupKey(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun tokenizeContactLookupTerms(raw: String): List<String> {
        return normalizeContactLookupKey(raw)
            .split(" ")
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(raw.trim()) }
    }

    private fun scoreContactPhoneCandidate(
        requestedKey: String,
        requestedTokens: List<String>,
        candidate: ContactPhoneCandidate,
    ): Int? {
        val candidateKey = normalizeContactLookupKey(candidate.displayName)
        if (candidateKey.isBlank()) return null
        val candidateTokens = candidateKey.split(" ").filter { it.isNotBlank() }
        val requestedPhrase = requestedTokens.joinToString(" ")
        val fuzzyTokenScore = scoreFuzzyContactTokens(requestedTokens, candidateTokens)
        return when {
            candidateKey == requestedKey -> 500
            candidateKey.startsWith("$requestedPhrase ") -> 420
            candidateTokens.any { it == requestedKey } -> 380
            requestedTokens.all { token -> candidateTokens.contains(token) } -> 320
            candidateKey.contains(requestedPhrase) -> 260
            candidateKey.contains(requestedKey.replace(" ", "")) -> 220
            fuzzyTokenScore != null -> fuzzyTokenScore
            else -> null
        }
    }

    private fun scoreFuzzyContactTokens(
        requestedTokens: List<String>,
        candidateTokens: List<String>,
    ): Int? {
        if (requestedTokens.isEmpty() || candidateTokens.isEmpty()) return null
        val unusedCandidateIndexes = candidateTokens.indices.toMutableSet()
        var exactMatches = 0
        var fuzzyMatches = 0
        var totalScore = 0

        for (requestedToken in requestedTokens) {
            val exactIndex = unusedCandidateIndexes.firstOrNull { candidateTokens[it] == requestedToken }
            if (exactIndex != null) {
                unusedCandidateIndexes.remove(exactIndex)
                exactMatches += 1
                totalScore += 80
                continue
            }

            val fuzzyMatch = unusedCandidateIndexes
                .mapNotNull { index ->
                    scoreSimilarContactToken(requestedToken, candidateTokens[index])?.let { score ->
                        index to score
                    }
                }
                .maxByOrNull { it.second }
                ?: return null

            unusedCandidateIndexes.remove(fuzzyMatch.first)
            fuzzyMatches += 1
            totalScore += fuzzyMatch.second
        }

        if (fuzzyMatches == 0) return null
        if (requestedTokens.size > 1 && exactMatches == 0) return null
        return 180 + totalScore
    }

    private fun scoreSimilarContactToken(requestedToken: String, candidateToken: String): Int? {
        if (requestedToken.length < 4 || candidateToken.length < 4) return null
        val normalizedRequested = normalizePhoneticContactToken(requestedToken)
        val normalizedCandidate = normalizePhoneticContactToken(candidateToken)
        val maxLength = maxOf(normalizedRequested.length, normalizedCandidate.length)
        if (maxLength == 0 || kotlin.math.abs(normalizedRequested.length - normalizedCandidate.length) > 2) {
            return null
        }
        val distance = levenshteinDistance(normalizedRequested, normalizedCandidate)
        return when {
            distance == 0 -> 74
            distance == 1 -> 68
            distance == 2 && maxLength >= 6 -> 56
            else -> null
        }
    }

    private fun normalizePhoneticContactToken(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace('q', 'k')
            .replace('x', 's')
            .replace('z', 's')
            .replace('v', 'f')
            .replace('y', 'i')
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)

        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                val substitutionCost = if (leftChar == rightChar) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            current.copyInto(previous)
        }
        return previous[right.length]
    }

    /** Resolves a contact name to an email address. Only pre-populates on unique match. */
    private fun resolveContactEmail(name: String): String? {
        return try {
            val aliasMatch = runBlocking { contactAliasRepository.getByAlias(name) }
            if (aliasMatch != null) {
                val aliasEmails = queryContactEmails(
                    selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    selectionArgs = arrayOf(aliasMatch.contactId),
                ).distinct()
                when (aliasEmails.size) {
                    1 -> {
                        Log.d(TAG, "Email resolved via alias '$name' → ${aliasEmails[0]}")
                        return aliasEmails[0]
                    }
                    0 -> Log.d(TAG, "Alias '${aliasMatch.alias}' found but no email found for contactId=${aliasMatch.contactId}")
                    else -> {
                        Log.d(TAG, "Alias '${aliasMatch.alias}' resolved to multiple emails — not pre-populating")
                        return null
                    }
                }
            }

            val nameLookups = buildList {
                add(name)
                aliasMatch?.displayName
                    ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                    ?.let(::add)
            }

            val matches = nameLookups
                .flatMap { lookupName ->
                    queryContactEmails(
                        selection = "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
                        selectionArgs = arrayOf("%$lookupName%"),
                    )
                }
                .distinct()
            when (matches.size) {
                0 -> { Log.d(TAG, "No email found for '$name'"); null }
                1 -> { Log.d(TAG, "Email resolved: '$name' → ${matches[0]}"); matches[0] }
                else -> { Log.d(TAG, "Multiple emails match '$name' — not pre-populating"); null }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted — cannot resolve email for '$name'", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Email lookup failed for '$name'", e)
            null
        }
    }

    private fun queryContactEmails(
        selection: String,
        selectionArgs: Array<String>,
    ): List<String> {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
        )
        return context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val address = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    )
                    if (!address.isNullOrBlank()) add(address)
                }
            }
        }.orEmpty()
    }

    private fun looksLikeEmailAddress(raw: String): Boolean {
        val trimmed = raw.trim()
        val atIndex = trimmed.indexOf('@')
        return atIndex > 0 && atIndex < trimmed.lastIndex && trimmed.substring(atIndex + 1).contains('.')
    }

    // ── List Management (#315 / #476 / #477) ─────────────────────────────────

    private fun normalizeListName(raw: String): String {
        val trimmed = raw.lowercase().trim()
        val alias = trimmed.removePrefix("my ").removePrefix("the ")
        return when (alias) {
            "shopping", "shopping list", "grocery list", "groceries", "grocery" -> "shopping list"
            "todo", "to do", "todos", "to-do", "to-do list" -> "to-do list"
            else -> trimmed
        }
    }

    private fun addToList(params: Map<String, String>): SkillResult {
        val item = params["item"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("add_to_list", "No item specified")
        val raw = params["list_name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("add_to_list", "No list name specified")
        val listName = normalizeListName(raw.lowercase())
        val items = runBlocking {
            listNameDao.insert(ListNameEntity(name = listName))
            listItemDao.insert(ListItemEntity(listName = listName, item = item))
            listItemDao.getByList(listName)
        }
        return SkillResult.DirectReply(
            "Added \"$item\" to your $listName.",
            presentation = buildListPreview(listName, items),
        )
    }

    private fun bulkAddToList(params: Map<String, String>): SkillResult {
        val raw = (params["list_name"] ?: "shopping list").lowercase().trim()
        val listName = normalizeListName(raw)
        val itemsParam = params["items"]
            ?: return SkillResult.Failure("bulk_add_to_list", "No items specified")
        val items: List<String> = try {
            val arr = org.json.JSONArray(itemsParam)
            (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            itemsParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        if (items.isEmpty()) return SkillResult.Failure("bulk_add_to_list", "No valid items to add")
        val currentItems = runBlocking {
            listNameDao.insert(ListNameEntity(name = listName))
            items.forEach { listItemDao.insert(ListItemEntity(listName = listName, item = it)) }
            listItemDao.getByList(listName)
        }
        return SkillResult.DirectReply(
            "Added ${items.size} item${if (items.size == 1) "" else "s"} to your $listName:\n" +
                items.joinToString(", "),
            presentation = buildListPreview(listName, currentItems),
        )
    }

    private fun createList(params: Map<String, String>): SkillResult {
        val raw = params["list_name"] ?: return SkillResult.Failure("create_list", "No list name specified")
        val name = normalizeListName(raw)
        runBlocking { listNameDao.insert(ListNameEntity(name = name)) }
        return SkillResult.DirectReply(
            "Created list \"$name\".",
            presentation = buildListPreview(name, emptyList(), "No items yet."),
        )
    }

    private fun getListItems(params: Map<String, String>): SkillResult {
        val raw = (params["list_name"] ?: "shopping list").lowercase().trim()
        val listName = normalizeListName(raw)
        val items = runBlocking { listItemDao.getByList(listName) }
        return if (items.isEmpty()) {
            SkillResult.DirectReply(
                "Your $listName is empty.",
                presentation = buildListPreview(listName, emptyList(), "No items yet."),
            )
        } else {
            val bullets = items.joinToString("\n") { "• ${it.item}" }
            SkillResult.DirectReply(
                "$listName (${items.size} item${if (items.size == 1) "" else "s"}):\n$bullets",
                presentation = buildListPreview(listName, items),
            )
        }
    }

    private fun removeFromList(params: Map<String, String>): SkillResult {
        val item = params["item"] ?: return SkillResult.Failure("remove_from_list", "No item specified")
        val raw = (params["list_name"] ?: "shopping list").lowercase().trim()
        val listName = normalizeListName(raw)
        val all = runBlocking { listItemDao.getByList(listName) }
        val match = all.firstOrNull { it.item.equals(item, ignoreCase = true) }
            ?: all.firstOrNull { it.item.contains(item, ignoreCase = true) }
            ?: return SkillResult.DirectReply("\"$item\" not found in $listName.")
        val remaining = runBlocking {
            listItemDao.deleteItem(match.id)
            listItemDao.getByList(listName)
        }
        return SkillResult.DirectReply(
            "Removed \"${match.item}\" from $listName.",
            presentation = buildListPreview(listName, remaining, "No items left."),
        )
    }

    // ── Smart Home (stub — pending #311 / #312) ───────────────────────────────

    private fun handleSmartHome(device: String, on: Boolean): SkillResult {
        val action = if (on) "turn on" else "turn off"
        return SkillResult.Failure(
            "smart_home",
            "Smart home control requires Home Assistant (#311) or Google Home (#312) integration. Cannot $action $device yet.",
        )
    }

    // ── Cancel Alarm ──────────────────────────────────────────────────────────

    private fun cancelAlarm(params: Map<String, String>): SkillResult {
        val label = params["label"]?.takeIf { it.isNotBlank() }
        if (label != null) {
            val cancelled = runBlocking { clockRepository.cancelAlarmsByLabel(label) }
            if (cancelled > 0) {
                val internalMessage = if (cancelled > 1) {
                    "Cancelled $cancelled app alarms matching $label."
                } else {
                    "Cancelled app alarm: $label."
                }
                return dismissAlarmInClockApp(label, internalMessage)
            }
        } else {
            val nextAlarm = runBlocking { clockRepository.cancelNextAlarm() }
            if (nextAlarm != null) {
                val internalMessage = "Cancelled next app alarm${nextAlarm.label?.let { value -> ": $value" } ?: ""}."
                return nextAlarm.label?.let { dismissAlarmInClockApp(it, internalMessage) }
                    ?: SkillResult.Success(internalMessage)
            }
        }
        return dismissAlarmInClockApp(label)
    }

    private fun dismissAlarmInClockApp(label: String?, prefixMessage: String? = null): SkillResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (label != null) {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            } else {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
        }
        return try {
            context.startActivity(intent)
            val clockMessage = if (label != null) {
                "Cancelling alarm in clock app: $label"
            } else {
                "Opening alarms to cancel"
            }
            SkillResult.Success(listOfNotNull(prefixMessage, clockMessage).joinToString(" "))
        } catch (e: ActivityNotFoundException) {
            prefixMessage?.let { SkillResult.Success(it) }
                ?: SkillResult.Failure("cancel_alarm", "No clock app found")
        }
    }

    // ── Get Weather ───────────────────────────────────────────────────────────

    private fun getWeather(params: Map<String, String>): SkillResult {
        val location = params["location"]
        val query = if (location != null) "weather in $location" else "weather"
        return try {
            val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Opening weather${if (location != null) " for $location" else ""}")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("get_weather", "No browser available")
        }
    }

    // ── Date Diff ─────────────────────────────────────────────────────────────

    private fun getDateDiff(params: Map<String, String>): SkillResult {
        val targetStr = params["target_date"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("get_date_diff", "No target_date provided")
        val today = LocalDate.now()
        val fromDate = params["from_date"]?.takeIf { it.isNotBlank() }
            ?.let { parseDateString(it) } ?: today
        val targetDate = parseDateString(targetStr)
            ?: return SkillResult.Failure("get_date_diff", "Could not parse date: \"$targetStr\"")

        val days = ChronoUnit.DAYS.between(fromDate, targetDate)
        val absDays = Math.abs(days)
        val weeks = absDays / 7
        val remainderDays = absDays % 7
        val targetFormatted = targetDate.format(
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
        )

        val reply = when {
            days == 0L -> "That's today — $targetFormatted."
            days > 0 -> buildString {
                append("$absDays day${if (absDays != 1L) "s" else ""} from now")
                if (weeks > 0) {
                    append(" ($weeks week${if (weeks != 1L) "s" else ""}")
                    if (remainderDays > 0) append(" and $remainderDays day${if (remainderDays != 1L) "s" else ""}")
                    append(")")
                }
                append(" — $targetFormatted.")
            }
            else -> buildString {
                append("$absDays day${if (absDays != 1L) "s" else ""} ago")
                if (weeks > 0) {
                    append(" ($weeks week${if (weeks != 1L) "s" else ""}")
                    if (remainderDays > 0) append(" and $remainderDays day${if (remainderDays != 1L) "s" else ""}")
                    append(")")
                }
                append(" — $targetFormatted.")
            }
        }
        val primaryText = when {
            days == 0L -> "Today"
            days > 0 -> "$absDays day${if (absDays != 1L) "s" else ""}"
            else -> "$absDays day${if (absDays != 1L) "s" else ""} ago"
        }
        val contextText = when {
            days == 0L -> targetFormatted
            days > 0 -> "Until $targetFormatted"
            else -> "Since $targetFormatted"
        }
        val breakdownText = if (weeks > 0) {
            buildString {
                append("$weeks week${if (weeks != 1L) "s" else ""}")
                if (remainderDays > 0) {
                    append(", $remainderDays day${if (remainderDays != 1L) "s" else ""}")
                }
            }
        } else null
        return SkillResult.DirectReply(
            reply,
            presentation = ToolPresentation.ComputedResult(
                primaryText = primaryText,
                contextText = contextText,
                breakdownText = breakdownText,
            ),
        )
    }

    private fun buildListPreview(
        listName: String,
        items: List<ListItemEntity>,
        emptyMessage: String? = null,
    ): ToolPresentation.ListPreview = ToolPresentation.ListPreview(
        title = listName,
        items = items.sortedByDescending { it.addedAt }.map { it.item },
        totalCount = items.size,
        emptyMessage = emptyMessage,
    )

    /**
     * Parses a date string in various natural formats, including named NZ/common holidays.
     * For dates without a year, picks the next upcoming occurrence.
     */
    private fun parseDateString(input: String): LocalDate? {
        val s = input.trim()
        val today = LocalDate.now()
        val year = today.year

        // Named holidays — next upcoming occurrence
        fun nextOccurrence(month: Int, day: Int): LocalDate {
            val candidate = LocalDate.of(year, month, day)
            return if (!candidate.isBefore(today)) candidate else LocalDate.of(year + 1, month, day)
        }
        // Nth weekday helper: e.g. nthWeekday(2, DayOfWeek.SUNDAY, Month.MAY, year) = 2nd Sunday in May
        fun nthWeekday(n: Int, dow: java.time.DayOfWeek, month: java.time.Month, y: Int): LocalDate {
            val first = LocalDate.of(y, month, 1)
            val firstMatch = first.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow))
            return firstMatch.plusWeeks((n - 1).toLong())
        }
        fun nextFloating(month: java.time.Month, nthSunday: Int): LocalDate {
            val candidate = nthWeekday(nthSunday, java.time.DayOfWeek.SUNDAY, month, year)
            return if (!candidate.isBefore(today)) candidate else nthWeekday(nthSunday, java.time.DayOfWeek.SUNDAY, month, year + 1)
        }
        when (s.lowercase().replace(Regex("[''`]"), "").trim()) {
            "christmas", "christmas day", "xmas" -> return nextOccurrence(12, 25)
            "new years day", "new years", "new year", "new year's day", "new year day" ->
                return LocalDate.of(year + 1, 1, 1).let {
                    if (!LocalDate.of(year, 1, 1).isBefore(today)) LocalDate.of(year, 1, 1) else it
                }
            "halloween" -> return nextOccurrence(10, 31)
            "waitangi day", "waitangi" -> return nextOccurrence(2, 6)
            "anzac day", "anzac" -> return nextOccurrence(4, 25)
            "valentines day", "valentine's day", "valentines" -> return nextOccurrence(2, 14)
            // Floating holidays (NZ): Mother's Day = 2nd Sunday May, Father's Day = 1st Sunday September
            "mothers day", "mother's day", "mothers" -> return nextFloating(java.time.Month.MAY, 2)
            "fathers day", "father's day", "fathers" -> return nextFloating(java.time.Month.SEPTEMBER, 1)
            "easter" -> {
                // Computus (anonymous Gregorian algorithm)
                fun easterDate(y: Int): LocalDate {
                    val a = y % 19; val b = y / 100; val c = y % 100
                    val d = b / 4; val e = b % 4; val f = (b + 8) / 25
                    val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
                    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7
                    val m = (a + 11 * h + 22 * l) / 451
                    val month = (h + l - 7 * m + 114) / 31
                    val day = ((h + l - 7 * m + 114) % 31) + 1
                    return LocalDate.of(y, month, day)
                }
                val thisYearEaster = easterDate(year)
                return if (!thisYearEaster.isBefore(today)) thisYearEaster else easterDate(year + 1)
            }
        }

        // Explicit date formats (ordered most-to-least specific)
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,                              // 2026-08-22
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),   // 22 August 2026
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),   // August 22 2026
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),  // August 22, 2026
            DateTimeFormatter.ofPattern("d/MM/yyyy"),                      // 22/08/2026
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),                     // 08/22/2026
            DateTimeFormatter.ofPattern("d-MM-yyyy"),                      // 22-08-2026
        )
        for (fmt in formatters) {
            try { return LocalDate.parse(s, fmt) } catch (_: Exception) {}
        }

        // Partial dates without year — pick next upcoming occurrence
        val partialFormatters = listOf(
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH),  // 22 August
            DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH),  // August 22
            DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH),    // August (1st of that month)
        )
        for (fmt in partialFormatters) {
            try {
                val md = MonthDay.parse(s, fmt)
                val candidate = md.atYear(year)
                return if (!candidate.isBefore(today)) candidate else md.atYear(year + 1)
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Get System Info ───────────────────────────────────────────────────────

    private fun getSystemInfo(): SkillResult {
        val sb = StringBuilder()
        // Storage
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val totalGb = totalBytes / (1024.0 * 1024 * 1024)
        val freeGb = freeBytes / (1024.0 * 1024 * 1024)
        sb.append("Storage: %.1f GB free of %.1f GB".format(freeGb, totalGb))
        // RAM
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeRamMb = memInfo.availMem / (1024 * 1024)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        sb.append(" | RAM: ${freeRamMb}MB free of ${totalRamMb}MB")
        return SkillResult.DirectReply(sb.toString())
    }

    // ── Save Memory ───────────────────────────────────────────────────────────

    private fun saveMemory(params: Map<String, String>): SkillResult {
        val content = params["content"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure("save_memory", "No content to save")
        return try {
            runBlocking {
                val vector = embeddingEngine.embed(content).takeIf { it.isNotEmpty() }
                memoryRepository.addCoreMemory(
                    content = content,
                    source = "agent",
                    embeddingVector = vector ?: floatArrayOf(),
                )
            }
            SkillResult.Success("✓ Saved: \"${content.take(100)}\"")
        } catch (e: Exception) {
            Log.e(TAG, "save_memory failed", e)
            SkillResult.Failure("save_memory", e.message ?: "Failed to save memory")
        }
    }

    // ── Set Brightness ────────────────────────────────────────────────────────

    private fun setBrightness(params: Map<String, String>): SkillResult {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return SkillResult.Success("Please grant permission to change brightness, then try again")
        }
        val direction = params["direction"]
        val value = params["value"]?.toIntOrNull()
        val isPercent = params["is_percent"] == "true"
        val currentBrightness = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
        )
        val targetBrightness = when {
            value != null && isPercent -> (value.coerceIn(0, 100) * 255 / 100.0).toInt()
            value != null -> value.coerceIn(0, 255)
            direction == "up" -> (currentBrightness + 51).coerceAtMost(255)   // +20%
            direction == "down" -> (currentBrightness - 51).coerceAtLeast(0)  // -20%
            direction == "max" -> 255
            direction == "min" -> 0
            else -> return SkillResult.Failure("set_brightness", "No brightness value or direction provided")
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetBrightness)
        val pct = (targetBrightness * 100 / 255)
        return SkillResult.Success("Brightness set to $pct%")
    }

    /**
     * Resolves a date string to a [LocalDate]. Accepts:
     * - ISO format: `YYYY-MM-DD` (and common variants like `YYYY-M-D`, `D-M-YYYY`, `D/M/YYYY`)
     * - Relative keywords: `today`, `tomorrow`
     * - Day names: `monday`…`sunday`, optionally prefixed with `next` (always skips to next occurrence)
     *
     * Returns null if the string cannot be resolved to a valid date.
     */
    private fun resolveDate(dateStr: String): LocalDate? {
        val input = dateStr.trim()
        val normalized = input.lowercase()
        val today = LocalDate.now()

        // Relative keywords
        when (normalized) {
            "today" -> return today
            "tomorrow" -> return today.plusDays(1)
        }

        // Day-of-week names with optional "next"/"this" prefix.
        // "this <day>" returns today if today matches (user means the current week's occurrence).
        // "next <day>" and bare day names always skip to the next future occurrence.
        val isThis = normalized.startsWith("this ")
        val dayName = normalized.removePrefix("next ").removePrefix("this ").trim()
        val targetDow: DayOfWeek? = when (dayName) {
            "monday" -> DayOfWeek.MONDAY
            "tuesday" -> DayOfWeek.TUESDAY
            "wednesday" -> DayOfWeek.WEDNESDAY
            "thursday" -> DayOfWeek.THURSDAY
            "friday" -> DayOfWeek.FRIDAY
            "saturday" -> DayOfWeek.SATURDAY
            "sunday" -> DayOfWeek.SUNDAY
            else -> null
        }
        if (targetDow != null) {
            return if (isThis && today.dayOfWeek == targetDow) today
            else today.with(TemporalAdjusters.next(targetDow))
        }

        // Strict ISO first, then progressively more lenient formats.
        // Year-defaulting formats (no year in string) use today's year.
        val today2 = today // capture for use in lambda below
        val formatters: List<Pair<DateTimeFormatter, ((java.time.temporal.TemporalAccessor) -> LocalDate)?>> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE to null,                           // 2026-04-16
            DateTimeFormatter.ofPattern("yyyy-M-d") to null,                    // 2026-4-16
            DateTimeFormatter.ofPattern("d-M-yyyy") to null,                    // 16-4-2026
            DateTimeFormatter.ofPattern("dd-MM-yyyy") to null,                  // 16-04-2026
            DateTimeFormatter.ofPattern("d/M/yyyy") to null,                    // 16/4/2026
            DateTimeFormatter.ofPattern("M/d/yyyy") to null,                    // 4/16/2026
            DateTimeFormatter.ofPattern("yyyy/MM/dd") to null,                  // 2026/04/16
            DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH) to null,  // 20 April 2026
            DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH) to null,   // 20 Apr 2026
            DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH) to null, // April 20, 2026
            DateTimeFormatter.ofPattern("MMMM d yyyy", java.util.Locale.ENGLISH) to null,  // April 20 2026
            // No-year variants — default to current year
            DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.ENGLISH) to
                { t: java.time.temporal.TemporalAccessor ->
                    LocalDate.of(today2.year, java.time.Month.from(t), java.time.MonthDay.from(t).dayOfMonth)
                },
            DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH) to
                { t: java.time.temporal.TemporalAccessor ->
                    LocalDate.of(today2.year, java.time.Month.from(t), java.time.MonthDay.from(t).dayOfMonth)
                },
            DateTimeFormatter.ofPattern("MMMM d", java.util.Locale.ENGLISH) to
                { t: java.time.temporal.TemporalAccessor ->
                    LocalDate.of(today2.year, java.time.Month.from(t), java.time.MonthDay.from(t).dayOfMonth)
                },
        )
        for ((fmt, resolver) in formatters) {
            try {
                val parsed = fmt.parse(input)
                return resolver?.invoke(parsed) ?: LocalDate.from(parsed)
            } catch (_: DateTimeParseException) { /* try next */ }
            catch (_: java.time.DateTimeException) { /* try next */ }
        }
        return null
    }

    /**
     * Parses a time string from the model into a [LocalTime], trying multiple common formats so
     * minor model hallucinations (extra zeros, missing padding, no-colon AM/PM) don't hard-fail.
     *
     * Pre-processing (applied in order):
     *  1. Strip trailing extra digits after HH:mm prefix — "18:0000" → "18:00"
     *  2. Pad a single-digit minute — "09:0" → "09:00", "9:5" → "9:05"
     *  3. Insert ":00" into bare hour+meridiem — "10pm" → "10:00pm", "9am" → "9:00am"
     *
     * Tried in order:
     *   HH:mm        — 18:00  (canonical)
     *   H:mm         — 9:00   (no hour padding)
     *   HH:mm:ss     — 18:00:00
     *   h:mm a       — 6:00 PM
     *   h:mma        — 6:00PM  (no space)
     *   hh:mm a      — 06:00 PM
     *   hh:mma       — 06:00PM
     */
    private fun resolveTime(timeStr: String): LocalTime? {
        val raw = timeStr.trim()

        // 0. Expand compact formats before any other normalisation.
        //    "630am" → "6:30am", "630" → "6:30", "0630" → "06:30", "1245pm" → "12:45pm"
        val expanded = Regex("""^(\d{1,2})(\d{2})\s*(am|pm|AM|PM)?$""").replace(raw) { m ->
            val meridiem = m.groupValues[3]
            "${m.groupValues[1]}:${m.groupValues[2]}${meridiem}"
        }

        // 0b. Recover malformed time strings produced by upstream number flattening.
        //     "36:00" -> "6:30", "37:00" -> "7:30", "80:00" -> "8:00"
        val recovered = recoverMalformedTime(expanded)

        // 1. Strip extra trailing digits after a valid HH:mm prefix (e.g. "18:0000" → "18:00").
        val stripped = Regex("""^(\d{1,2}:\d{2})\d+(.*)$""").replace(recovered) { m ->
            m.groupValues[1] + m.groupValues[2]
        }

        // 2. Pad a single-digit minute value (e.g. "09:0" → "09:00", "9:5" → "9:05").
        val padded = Regex("""^(\d{1,2}):(\d)(?!\d)(.*)$""").replace(stripped) { m ->
            "${m.groupValues[1]}:0${m.groupValues[2]}${m.groupValues[3]}"
        }

        // 3. Insert ":00" for bare hour+meridiem — only for valid 12h range (1-12).
        //    Reject "13pm" / "0am" here so we don't produce a string that silently fails later.
        val input = Regex("""^(\d{1,2})\s*(am|pm|AM|PM)$""").replace(padded) { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            if (h in 1..12) "${m.groupValues[1]}:00${m.groupValues[2]}" else m.value
        }.trim()

        val formatters = listOf(
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mma", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("hh:mma", java.util.Locale.ENGLISH),
        )
        for (fmt in formatters) {
            try {
                return LocalTime.parse(input, fmt)
            } catch (_: DateTimeParseException) { /* try next */ }
        }
        Log.w(TAG, "resolveTime: could not parse '$raw' (expanded='$expanded', recovered='$recovered', normalized='$input')")
        return null
    }

    private fun recoverMalformedTime(input: String): String {
        val flattenedThirty = Regex("""^(3[1-9]|4[0-2]):00(\s*(?:am|pm|AM|PM))?$""")
        flattenedThirty.matchEntire(input)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return@let
            val meridiem = match.groupValues[2]
            val hour = rawHour - 30
            if (hour in 1..12) {
                return "$hour:30$meridiem"
            }
        }

        val flattenedOclock = Regex("""^([1-9]|1[0-2])0:00(\s*(?:am|pm|AM|PM))?$""")
        flattenedOclock.matchEntire(input)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val meridiem = match.groupValues[2]
            return "$hour:00$meridiem"
        }

        return input
    }
}
