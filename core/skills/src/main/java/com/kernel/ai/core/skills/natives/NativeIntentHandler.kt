package com.kernel.ai.core.skills.natives

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
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
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", params["message"] ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return SkillResult.Success("SMS composer opened.")
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private fun setAlarm(params: Map<String, String>): SkillResult {
        // Prefer `time` string (e.g. "10pm", "09:05") over raw hours/minutes so the model
        // never has to do 12h→24h conversion — resolveTime() handles it reliably in Kotlin.
        val (hours, minutes): Pair<Int, Int> = params["time"]?.let { t ->
            resolveTime(t)?.let { it.hour to it.minute }
        } ?: ((params["hours"]?.toIntOrNull() ?: 8) to (params["minutes"]?.toIntOrNull() ?: 0))
        val day = params["day"]?.trim()?.lowercase()
        val isTomorrow = day == "tomorrow"

        // NOTE: AlarmClock.EXTRA_DAYS is intentionally NOT used here.
        // EXTRA_DAYS creates a repeating weekly alarm, not a one-time future alarm.
        // The clock app opens pre-filled with the time; the user confirms the date.
        //
        // For "tomorrow" alarms we prefix EXTRA_MESSAGE with "TOMORROW:" so the label is
        // visible in the clock app, reminding the user to verify the date before confirming.
        val baseLabel = params["label"]?.takeIf { it.isNotBlank() }
        val messageLabel = when {
            isTomorrow && baseLabel != null -> "TOMORROW: $baseLabel"
            isTomorrow -> "TOMORROW"
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
            val tomorrowWarning = if (isTomorrow) {
                " ⚠ Your clock app schedules by time only — please verify the date is set to tomorrow before confirming."
            } else ""
            SkillResult.Success(
                "Clock app opened — alarm$dayLabel at %02d:%02d. Please confirm in your clock app.$tomorrowWarning"
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
