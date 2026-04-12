package com.kernel.ai.core.inference

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Native skill implementations exposed as LiteRT-LM tools.
 * FunctionGemma calls these directly via the SDK's ToolSet API — no JSON parsing required.
 *
 * Each `@Tool`-annotated method is invoked by the SDK when FunctionGemma decides it matches
 * a user request. The return value is fed back to the model as a tool response before the
 * model generates its final text reply.
 */
@Singleton
class KernelAIToolSet @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Volatile private var toolCalledInThisTurn = false

    /** Call before each [FunctionGemmaRouter.handle] invocation to reset the tool-called flag. */
    fun resetTurnState() {
        toolCalledInThisTurn = false
    }

    /** Returns true if any `@Tool` method was invoked during the current turn. */
    fun wasToolCalled(): Boolean = toolCalledInThisTurn

    @Tool(description = "Turns the device flashlight/torch on")
    fun turnOnFlashlight(): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: turnOnFlashlight")
        return setTorch(true)
    }

    @Tool(description = "Turns the device flashlight/torch off")
    fun turnOffFlashlight(): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: turnOffFlashlight")
        return setTorch(false)
    }

    @Tool(description = "Gets the current weather for a location")
    fun getWeather(
        @ToolParam(description = "The city or location to get weather for") location: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: getWeather location=$location")
        // TODO: wire to real weather API — return placeholder for now
        return mapOf("result" to "Weather lookup for $location is not yet available offline.")
    }

    @Tool(description = "Sets a timer for a specified duration")
    fun setTimer(
        @ToolParam(description = "Duration as a number with unit, e.g. '5 minutes', '30 seconds', '1 hour', '90 seconds'") duration: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: setTimer duration=$duration")
        // Parse duration string to milliseconds for AlarmManager
        val millis = parseDuration(duration)
        return if (millis > 0) {
            // TODO: wire to AlarmManager — placeholder for now
            mapOf("result" to "success", "message" to "Timer set for $duration")
        } else {
            mapOf("result" to "error", "error" to "Could not understand duration: $duration")
        }
    }

    @Tool(description = "Gets the current local time and date. Use this when the user asks 'what time is it', 'what is the time', 'what is the date', or 'what day is it'")
    fun getCurrentTime(): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: getCurrentTime")
        val now = java.time.LocalDateTime.now()
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
        val dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy", java.util.Locale.ENGLISH))
        return mapOf("time" to timeStr, "date" to dateStr, "result" to "success")
    }

    @Tool(description = "Saves a note or memory for future reference")
    fun saveMemory(
        @ToolParam(description = "The content to remember") content: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: saveMemory (${content.length} chars)")
        // TODO: wire to Room notes DB
        return mapOf("result" to "Memory saved: $content")
    }

    @Tool(description = "Runs an Android system action such as opening settings, setting an alarm, creating a calendar event, or sending an email")
    fun runIntent(
        @ToolParam(description = "The action to perform. One of: SET_ALARM, OPEN_WIFI_SETTINGS, OPEN_BLUETOOTH_SETTINGS, CREATE_CALENDAR_EVENT, SEND_EMAIL") action: String,
        @ToolParam(description = "JSON string of parameters. SET_ALARM: {\"hours\":7,\"minutes\":30,\"message\":\"label\"}. CREATE_CALENDAR_EVENT: {\"title\":\"Meeting\",\"startEpochMs\":1234567890000,\"endEpochMs\":1234571490000}. SEND_EMAIL: {\"subject\":\"Hello\",\"body\":\"text\"}") parameters: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        Log.d(TAG, "ToolSet: runIntent action=$action params=$parameters")
        return try {
            val params = org.json.JSONObject(parameters.ifBlank { "{}" })
            when (action.uppercase()) {
                "SET_ALARM" -> {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, params.optInt("hours", 8))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, params.optInt("minutes", 0))
                        params.optString("message").takeIf { it.isNotEmpty() }?.let {
                            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    mapOf("result" to "success", "action" to action)
                }
                "OPEN_WIFI_SETTINGS" -> {
                    context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    mapOf("result" to "success")
                }
                "OPEN_BLUETOOTH_SETTINGS" -> {
                    context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    mapOf("result" to "success")
                }
                "CREATE_CALENDAR_EVENT" -> {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, params.optString("title", "New Event"))
                        val startMs = params.optLong("startEpochMs")
                        if (startMs > 0) putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                        val endMs = params.optLong("endEpochMs")
                        if (endMs > 0) putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    mapOf("result" to "success")
                }
                "SEND_EMAIL" -> {
                    // Do NOT populate EXTRA_EMAIL from LLM output to prevent prompt-injection
                    // exfiltration — the user must enter the recipient themselves in the mail app.
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "message/rfc822"
                        putExtra(Intent.EXTRA_SUBJECT, params.optString("subject", ""))
                        putExtra(Intent.EXTRA_TEXT, params.optString("body", ""))
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    mapOf("result" to "success")
                }
                else -> mapOf("result" to "error", "error" to "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "runIntent failed: ${e.message}", e)
            mapOf("result" to "error", "error" to (e.message ?: "Unknown error"))
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun parseDuration(duration: String): Long {
        val lower = duration.trim().lowercase()
        val regex = Regex("""(\d+(?:\.\d+)?)\s*(second|sec|minute|min|hour|hr)s?""")
        val match = regex.find(lower) ?: return -1L
        val value = match.groupValues[1].toDoubleOrNull() ?: return -1L
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("hour") || unit.startsWith("hr") -> (value * 3_600_000).toLong()
            unit.startsWith("min") -> (value * 60_000).toLong()
            else -> (value * 1_000).toLong()
        }
    }

    private fun setTorch(enabled: Boolean): Map<String, String> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
                mapOf("result" to "success")
            } else {
                mapOf("result" to "error", "error" to "No flash unit found on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setTorch($enabled) failed: ${e.message}", e)
            mapOf("result" to "error", "error" to (e.message ?: "Unknown error"))
        }
    }
}
