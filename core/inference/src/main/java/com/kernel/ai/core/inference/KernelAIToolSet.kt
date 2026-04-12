package com.kernel.ai.core.inference

import android.content.Context
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

    @Tool(description = "Gets the current date and time on the device")
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
