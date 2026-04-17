package com.kernel.ai.core.skills.natives

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.skills.SkillResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

private const val TAG = "KernelAI"

/**
 * Central dispatcher for all native Android operations triggered via the [run_intent][RunIntentSkill] gateway.
 *
 * Mirrors Google AI Edge Gallery's IntentHandler pattern — a single class mapping
 * [intentName] strings to concrete Android APIs or system Intents.
 *
 * Supported intents:
 *   toggle_flashlight_on    — Camera2 torch on
 *   toggle_flashlight_off   — Camera2 torch off
 *   send_email              — ACTION_SEND mail chooser (params: subject, body)
 *   send_sms                — ACTION_SENDTO SMS composer (params: message)
 *   set_alarm               — AlarmClock.ACTION_SET_ALARM (params: hours, minutes, label)
 *   set_timer               — AlarmClock.ACTION_SET_TIMER (params: duration_seconds, label)
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
 *   add_to_list             — Insert item into list (params: item, list_name?) — returns DirectReply
 *   create_list             — Create a named list (params: list_name)
 *   get_list_items          — Retrieve unchecked items from a list (params: list_name?)
 *   remove_from_list        — Remove an item from a list (params: item, list_name?)
 *   smart_home_on/off       — Stub pending HA/Google Home (#311/#312) (params: device)
 *   cancel_alarm            — AlarmClock.ACTION_DISMISS_ALARM (params: label?)
 *   get_weather             — Opens Google search for weather (params: location?)
 *   get_system_info         — Returns storage and RAM info — returns DirectReply
 *   save_memory             — Saves content to long-term core memory (params: content)
 *   set_brightness          — Sets screen brightness (params: value?, direction?, is_percent?)
 */
@Singleton
class NativeIntentHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduledAlarmDao: ScheduledAlarmDao,
    private val listItemDao: ListItemDao,
    private val listNameDao: ListNameDao,
    private val contactAliasRepository: ContactAliasRepository,
    private val memoryRepository: MemoryRepository,
    private val embeddingEngine: EmbeddingEngine,
) {

    fun handle(intentName: String, params: Map<String, String>): SkillResult {
        Log.d(TAG, "NativeIntentHandler.handle: intent=$intentName params=$params")
        return try {
            when (intentName) {
                "toggle_flashlight_on" -> setTorch(true)
                "toggle_flashlight_off" -> setTorch(false)
                "send_email" -> sendEmail(params)
                "send_sms" -> sendSms(params)
                "set_alarm" -> setAlarm(params)
                "set_timer" -> setTimer(params)
                "cancel_timer" -> cancelTimer()
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
                "create_list" -> createList(params)
                "get_list_items" -> getListItems(params)
                "remove_from_list" -> removeFromList(params)
                "smart_home_on" -> handleSmartHome(params["device"] ?: "device", true)
                "smart_home_off" -> handleSmartHome(params["device"] ?: "device", false)
                "get_weather" -> getWeather(params)
                "get_system_info" -> getSystemInfo()
                "save_memory" -> saveMemory(params)
                "set_brightness" -> setBrightness(params)
                else -> SkillResult.Failure("run_intent", "Unknown intent: $intentName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NativeIntentHandler.handle($intentName) failed", e)
            SkillResult.Failure("run_intent", e.message ?: "Unknown error")
        }
    }

    // ── Torch ─────────────────────────────────────────────────────────────────

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
        // Resolve recipient from on-device contacts only (never from raw LLM text).
        // Only pre-populate if exactly one match is found — avoids wrong-recipient risk.
        val contact = params["contact"]
        val resolvedEmail = contact?.let { resolveContactEmail(it) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            if (resolvedEmail != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(resolvedEmail))
            putExtra(Intent.EXTRA_SUBJECT, params["subject"] ?: "")
            putExtra(Intent.EXTRA_TEXT, params["body"] ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Send email").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        val recipientLabel = resolvedEmail?.let { " to $it" } ?: ""
        return SkillResult.Success("Email composer opened$recipientLabel.")
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private fun sendSms(params: Map<String, String>): SkillResult {
        val contact = params["contact"] ?: params["phone"]
        val number = contact?.let { resolveContactNumber(it) ?: it }
        val smsUri = if (number != null) "smsto:${Uri.encode(number)}" else "smsto:"
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

        if (resolvedDate != null) {
            // Schedule a real exact alarm via AlarmManager
            val triggerAt = resolvedDate
                .atTime(resolvedTime)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            if (triggerAt <= System.currentTimeMillis()) {
                return SkillResult.Failure("run_intent", "That time has already passed.")
            }

            val alarmId = UUID.randomUUID().toString()
            val alarmEntity = ScheduledAlarmEntity(
                id = alarmId,
                triggerAtMillis = triggerAt,
                label = label,
                createdAt = System.currentTimeMillis(),
            )
            runBlocking { scheduledAlarmDao.insert(alarmEntity) }

            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val alarmIntent = Intent().apply {
                component = android.content.ComponentName(
                    context.packageName,
                    "com.kernel.ai.alarm.AlarmBroadcastReceiver",
                )
                putExtra("alarm_label", label ?: "Alarm")
                putExtra("alarm_id", alarmId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId.hashCode(),
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)

            val formatter = DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mma")
                .withZone(ZoneId.systemDefault())
            val formattedTime = formatter.format(Instant.ofEpochMilli(triggerAt))
            return SkillResult.Success(
                "Alarm set for $formattedTime${if (label != null) " — $label" else ""}"
            )
        }

        // No date resolved — fall back to clock app intent (existing behaviour)
        val (hours, minutes) = resolvedTime.hour to resolvedTime.minute
        val dayDisplay = day?.replaceFirstChar { it.uppercase() }
        val isWeekday = day?.lowercase() in setOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
        )
        val isTomorrow = day?.lowercase() == "tomorrow"
        val messageLabel = when {
            isTomorrow && label != null -> "TOMORROW: $label"
            isTomorrow -> "TOMORROW"
            isWeekday && label != null -> "$dayDisplay: $label"
            isWeekday -> dayDisplay
            else -> label
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hours)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            messageLabel?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return SkillResult.Failure("run_intent", "No clock app found to set an alarm.")
        }
        return try {
            context.startActivity(intent)
            val dayLabel = day?.takeIf { it.isNotBlank() }
                ?.let { " for ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""
            val dayWarning = when {
                isTomorrow -> " ⚠ Your clock app schedules by time only — please verify the date is set to tomorrow before confirming."
                isWeekday -> " ⚠ Your clock app schedules by time only — please verify the date is set to $dayDisplay before confirming."
                else -> ""
            }
            SkillResult.Success(
                "Clock app opened — alarm$dayLabel at %02d:%02d. Please confirm in your clock app.$dayWarning"
                    .format(hours, minutes)
            )
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("run_intent", "No clock app found to set an alarm.")
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun setTimer(params: Map<String, String>): SkillResult {
        val seconds = params["duration_seconds"]?.toIntOrNull()
            ?: return SkillResult.Failure("run_intent", "duration_seconds is required and must be an integer.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            params["label"]?.takeIf { it.isNotBlank() }?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return SkillResult.Failure("run_intent", "No clock app found to set a timer.")
        }
        return try {
            context.startActivity(intent)
            val mins = seconds / 60
            val secs = seconds % 60
            val label = when {
                mins > 0 && secs > 0 -> "$mins min $secs sec"
                mins > 0 -> "$mins minute${if (mins != 1) "s" else ""}"
                else -> "$seconds seconds"
            }
            SkillResult.Success("Timer set for $label.")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("run_intent", "No clock app found to set a timer.")
        }
    }

    private fun cancelTimer(): SkillResult {
        // ACTION_DISMISS_TIMER (API 26+) stops any ringing timer and cancels
        // all running timers — covers both "stop that noise" and "cancel timer".
        val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            SkillResult.Success("Timer cancelled.")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("run_intent", "No clock app found to cancel the timer.")
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
            KeyEvent.KEYCODE_MEDIA_PAUSE    -> "Paused"
            KeyEvent.KEYCODE_MEDIA_STOP     -> "Stopped"
            KeyEvent.KEYCODE_MEDIA_NEXT     -> "Skipped to next track"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous track"
            else -> "Done"
        }
        return SkillResult.Success(label)
    }

    // ── Plex ──────────────────────────────────────────────────────────────────

    private fun playPlex(params: Map<String, String>): SkillResult {
        val title = params["title"] ?: return SkillResult.Failure("play_plex", "No title provided")
        return try {
            val plexIntent = Intent(Intent.ACTION_VIEW, Uri.parse("plex://play?media=$title")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(plexIntent)
            SkillResult.Success("Opening Plex for: $title")
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
        val query = params["query"] ?: return SkillResult.Failure("play_plexamp", "No search query provided")
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "tv.plex.labs.plexamp"
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Searching Plexamp for: $query")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("play_plexamp", "Plexamp app not installed")
        }
    }

    // ── YouTube Music ──

    private fun playYoutubeMusic(params: Map<String, String>): SkillResult {
        val query = params["query"] ?: return SkillResult.Failure("play_youtube_music", "No search query provided")
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.google.android.apps.youtube.music"
                putExtra(SearchManager.QUERY, query)
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
        val pm = context.packageManager
        // Try to find a matching app by label
        val matchingApp = pm.getInstalledApplications(0).firstOrNull { appInfo ->
            pm.getApplicationLabel(appInfo).toString().equals(appName, ignoreCase = true)
        }
        val launchIntent = matchingApp?.let { pm.getLaunchIntentForPackage(it.packageName) }
        return if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            SkillResult.Success("Opening $appName")
        } else {
            SkillResult.Failure("open_app", "Could not find app: $appName")
        }
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
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SkillResult.Success("Opening dialer for $contact")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("make_call", "No phone app available")
        }
    }

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

        // 2. ContactsContract fuzzy search — only pre-populate if unique match
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val matches = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    matches += cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                }
                when (matches.size) {
                    0 -> { Log.d(TAG, "Contact not found for '$name'"); null }
                    1 -> { Log.d(TAG, "Contact resolved: '$name' → ${matches[0]}"); matches[0] }
                    else -> { Log.d(TAG, "Multiple contacts match '$name' — not pre-populating"); null }
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

    /** Resolves a contact name to an email address. Only pre-populates on unique match. */
    private fun resolveContactEmail(name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
            )
            val selection = "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val matches = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val address = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    )
                    if (!address.isNullOrBlank()) matches += address
                }
                when (matches.size) {
                    0 -> { Log.d(TAG, "No email found for '$name'"); null }
                    1 -> { Log.d(TAG, "Email resolved: '$name' → ${matches[0]}"); matches[0] }
                    else -> { Log.d(TAG, "Multiple emails match '$name' — not pre-populating"); null }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted — cannot resolve email for '$name'", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Email lookup failed for '$name'", e)
            null
        }
    }

    // ── List Management (#315 / #476 / #477) ─────────────────────────────────

    private fun normalizeListName(raw: String): String = when (raw.lowercase().trim()) {
        "grocery list", "groceries", "grocery" -> "shopping list"
        "todo", "to do", "todos", "to-do" -> "to-do list"
        else -> raw.lowercase().trim()
    }

    private fun addToList(params: Map<String, String>): SkillResult {
        val item = params["item"] ?: return SkillResult.Failure("add_to_list", "No item specified")
        val raw = (params["list_name"] ?: "shopping list").lowercase().trim()
        val listName = normalizeListName(raw)
        runBlocking {
            listNameDao.insert(ListNameEntity(name = listName))
            listItemDao.insert(ListItemEntity(listName = listName, item = item))
        }
        return SkillResult.DirectReply("Added \"$item\" to your $listName.")
    }

    private fun createList(params: Map<String, String>): SkillResult {
        val raw = params["list_name"] ?: return SkillResult.Failure("create_list", "No list name specified")
        val name = raw.lowercase().trim()
        runBlocking { listNameDao.insert(ListNameEntity(name = name)) }
        return SkillResult.DirectReply("Created list \"$name\".")
    }

    private fun getListItems(params: Map<String, String>): SkillResult {
        val raw = (params["list_name"] ?: "shopping list").lowercase().trim()
        val listName = normalizeListName(raw)
        val items = runBlocking { listItemDao.getByList(listName) }
        return if (items.isEmpty()) {
            SkillResult.DirectReply("Your $listName is empty.")
        } else {
            val bullets = items.joinToString("\n") { "• ${it.item}" }
            SkillResult.DirectReply("$listName (${items.size} item${if (items.size == 1) "" else "s"}):\n$bullets")
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
        runBlocking { listItemDao.deleteItem(match.id) }
        return SkillResult.DirectReply("Removed \"${match.item}\" from $listName.")
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
        val label = params["label"]
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
            SkillResult.Success(if (label != null) "Cancelling alarm: $label" else "Opening alarms to cancel")
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("cancel_alarm", "No clock app found")
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

        // 1. Strip extra trailing digits after a valid HH:mm prefix (e.g. "18:0000" → "18:00").
        val stripped = Regex("""^(\d{1,2}:\d{2})\d+(.*)$""").replace(expanded) { m ->
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
        Log.w(TAG, "resolveTime: could not parse '$raw' (expanded='$expanded', normalized='$input')")
        return null
    }
}
