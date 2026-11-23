package com.kernel.ai.debug.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.VoiceOutputPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Debug-only foreground service that runs one TTS baseline off the broadcast thread (a broadcast's
 * `goAsync` window is about 60 s, too short for a multi-pass run). Started by [InflectBaselineReceiver].
 * Writes `files/inflect_baseline/<label>.json` and logs `inflect baseline done` or `inflect baseline failed`.
 */
class DebugTtsBaselineService : Service() {
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "inflect baseline already running; ignoring start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val extras = Intent(intent ?: Intent())
        Thread({
            try {
                execute(extras)
            } finally {
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }, "debug-tts-baseline").start()
        return START_NOT_STICKY
    }

    private fun execute(intent: Intent) {
        val label = intent.getStringExtra(DebugTtsBaselineRequest.EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: "run"
        val output = debugTtsBaselineOutput(this, label)
        var lane: DebugTtsLane? = null
        try {
            val request = DebugTtsBaselineRequest.from(intent)
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, DebugTtsBaselineEntryPoint::class.java)
            val voice = runBlocking { entryPoint.voiceOutputPreferences().selectedSherpaVoice.first() }
            val espeak = File(voice.voiceDir(this), ESPEAK_DATA_DIR)
            check(espeak.isDirectory) { "selected voice ${voice.name} has no $ESPEAK_DATA_DIR at ${espeak.path}" }
            val models = InflectMicroModelSpec.modelDirectory(this)
            val missing = InflectMicroModelSpec.requiredModels.firstOrNull { !File(models, it.fileName).isFile }
            check(missing == null) { "missing ${missing?.fileName} in ${models.path}" }

            val created = InflectMicroDebugLane(models, espeak)
            lane = created
            val json = DebugTtsBaselineRun(this, created, voice, espeak).execute(request)
            writeJson(output, json)
            Log.i(TAG, "inflect baseline done path=${output.absolutePath}")
        } catch (error: Throwable) {
            Log.e(TAG, "inflect baseline failed", error)
            writeJson(
                output,
                JSONObject()
                    .put("schema", "debug-tts-baseline/2")
                    .put("status", "failed")
                    .put("label", label)
                    .put("error", "${error::class.java.simpleName}: ${error.message}"),
            )
        } finally {
            lane?.close()
        }
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Debug TTS baseline", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Debug-only TTS synthesis baseline run" },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Debug TTS baseline running")
        .setContentText("Synthesising the fixed corpus for evidence")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "KernelAI"
        private const val CHANNEL_ID = "debug_tts_baseline"
        private const val NOTIFICATION_ID = 2088
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugTtsBaselineEntryPoint {
    fun voiceOutputPreferences(): VoiceOutputPreferences
}
