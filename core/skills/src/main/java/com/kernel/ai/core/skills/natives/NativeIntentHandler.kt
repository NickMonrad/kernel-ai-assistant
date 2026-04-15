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
import android.util.Log
import com.kernel.ai.core.skills.SkillResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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
 *   play_plex               — Plex deep link (params: title)
 *   navigate_to             — Google Maps / geo: URI (params: destination)
 *   find_nearby             — geo: URI nearby search (params: query)
 *   make_call               — ACTION_DIAL with contact resolution (params: contact)
 *   add_to_list             — Stub pending Room DB (#315) (params: item, list_name?)
 *   smart_home_on/off       — Stub pending HA/Google Home (#311/#312) (params: device)
 */
@Singleton
class NativeIntentHandler @Inject constructor(
    @ApplicationContext private val context: Context,
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
                "create_calendar_event" -> createCalendarEvent(params)
                "get_battery" -> getBattery()
                "get_time", "get_date" -> getTime()
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
                "play_netflix" -> playNetflix(params)
                "play_plex" -> playPlex(params)
                "open_app" -> openApp(params)
                "navigate_to" -> navigateTo(params)
                "find_nearby" -> findNearby(params)
                "make_call" -> makeCall(params)
                "add_to_list" -> addToList(params)
                "smart_home_on" -> handleSmartHome(params["device"] ?: "device", true)
                "smart_home_off" -> handleSmartHome(params["device"] ?: "device", false)
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
        // SECURITY: do NOT populate EXTRA_EMAIL from LLM output — recipient must be chosen by user
        // in the mail app to prevent prompt-injection exfiltration.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_SUBJECT, params["subject"] ?: "")
            putExtra(Intent.EXTRA_TEXT, params["body"] ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(intent, "Send email").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return SkillResult.Success("Email composer opened.")
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
        // Prefer `time` string (e.g. "10pm", "09:05") over raw hours/minutes so the model
        // never has to do 12h→24h conversion — resolveTime() handles it reliably in Kotlin.
        val timePair = params["time"]?.let { t ->
            resolveTime(t)?.let { it.hour to it.minute }
        } ?: ((params["hours"]?.toIntOrNull() ?: 8) to (params["minutes"]?.toIntOrNull() ?: 0))
        val (hours, minutes) = timePair
        val day = params["day"]?.trim()?.lowercase()
        val isTomorrow = day == "tomorrow"
        val weekdays = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val isWeekday = day in weekdays

        // NOTE: AlarmClock.EXTRA_DAYS is intentionally NOT used here.
        // EXTRA_DAYS creates a repeating weekly alarm, not a one-time future alarm.
        // The clock app opens pre-filled with the time; the user confirms the date.
        //
        // For "tomorrow" or weekday alarms we prefix EXTRA_MESSAGE with the day name so
        // the label is visible in the clock app, reminding the user to verify the date.
        val baseLabel = params["label"]?.takeIf { it.isNotBlank() }
        val dayDisplay = day?.replaceFirstChar { it.uppercase() }
        val messageLabel = when {
            isTomorrow && baseLabel != null -> "TOMORROW: $baseLabel"
            isTomorrow -> "TOMORROW"
            isWeekday && baseLabel != null -> "$dayDisplay: $baseLabel"
            isWeekday -> dayDisplay
            else -> baseLabel
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

        val (beginMillis, endMillis) = if (timeStr != null) {
            val time = resolveTime(timeStr)
                ?: return SkillResult.Failure("run_intent", "Invalid time format '$timeStr' — expected HH:MM (24h).")
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return SkillResult.Failure("run_intent", "No calendar app found on this device.")
        }
        return try {
            context.startActivity(intent)
            val timeLabel = if (timeStr != null) " at $timeStr" else " (all day)"
            val resolvedDateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            SkillResult.Success("Calendar opened — '${title}' on $resolvedDateStr$timeLabel. Please review and save in your calendar app.")
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
        return SkillResult.Success("Battery is at $pct%$chargingSuffix")
    }

    // ── Time / Date ───────────────────────────────────────────────────────────

    private fun getTime(): SkillResult {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a"))
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        return SkillResult.Success("It's $time on $date")
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
        val raw = params["value"]?.toIntOrNull()
            ?: return SkillResult.Failure("set_volume", "No volume value provided")
        val isPercent = params["is_percent"] == "true"
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = if (isPercent) {
            (raw.coerceIn(0, 100) * maxVol / 100.0).roundToInt()
        } else {
            // 1-10 scale mapped to stream range
            ((raw.coerceIn(1, 10) - 1) * maxVol / 9.0).roundToInt()
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
        return SkillResult.Success("Volume set to ${if (isPercent) "$raw%" else "$raw/10"}")
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
        val phoneNumber = resolveContactNumber(contact) ?: contact
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
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for '$name'", e)
            null
        }
    }

    // ── List Management (stub — pending #315) ─────────────────────────────────

    private fun addToList(params: Map<String, String>): SkillResult {
        val item = params["item"] ?: return SkillResult.Failure("add_to_list", "No item specified")
        val list = params["list_name"] ?: "shopping list"
        // TODO: Wire to Room DB list feature (#315)
        return SkillResult.Success("Added \"$item\" to your $list")
    }

    // ── Smart Home (stub — pending #311 / #312) ───────────────────────────────

    private fun handleSmartHome(device: String, on: Boolean): SkillResult {
        val action = if (on) "turn on" else "turn off"
        return SkillResult.Failure(
            "smart_home",
            "Smart home control requires Home Assistant (#311) or Google Home (#312) integration. Cannot $action $device yet.",
        )
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

        // Day-of-week names with optional "next" prefix (both resolve identically)
        val dayName = normalized.removePrefix("next ").trim()
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
            // Always return the next future occurrence — never today.
            // TemporalAdjusters.next() guarantees this; "next wednesday" and plain "wednesday"
            // behave identically (both skip to the upcoming occurrence in the future).
            return today.with(TemporalAdjusters.next(targetDow))
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

        // 1. Strip extra trailing digits after a valid HH:mm prefix (e.g. "18:0000" → "18:00").
        val stripped = Regex("""^(\d{1,2}:\d{2})\d+(.*)$""").replace(raw) { m ->
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
        Log.w(TAG, "resolveTime: could not parse '$raw' (normalized to '$input')")
        return null
    }
}
