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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Debug-only foreground service that runs one TTS baseline off the broadcast thread (a broadcast's `goAsync`
 * window is about 60 s, too short for a multi-pass run). Started by [DebugTtsBaselineReceiver].
 * Writes `files/tts_baseline/<lane>/<label>.json` and logs `tts baseline done` or `tts baseline failed`.
 */
class DebugTtsBaselineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running.compareAndSet(false, true)) {
            // The active run owns the foreground state; a duplicate start must not stop the service.
            Log.w(TAG, "tts baseline already running; ignoring duplicate start")
            return START_NOT_STICKY
        }
        val extras = Intent(intent ?: Intent())
        Thread({
            try {
                execute(extras)
            } finally {
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Unconditional: the single active run is the only reason this service is alive.
                stopSelf()
            }
        }, "debug-tts-baseline").start()
        return START_NOT_STICKY
    }

    private fun execute(intent: Intent) {
        var request: DebugTtsBaselineRequest? = null
        var lane: DebugTtsLane? = null
        try {
            val validated = DebugTtsBaselineRequest.from(intent)
            request = validated
            val output = debugTtsBaselineOutput(this, validated.lane, validated.label)
            val created = createLane(validated.lane)
            lane = created
            // A native call that never returns (seen on ExecuTorch 1.5.1 after a rejected input) would leave no JSON.
            val json = withDeadline(RUN_DEADLINE_MS) { DebugTtsBaselineRun(this, created).execute(validated) }
            writeJson(output, json)
            Log.i(TAG, "tts baseline done lane=${validated.lane} path=${output.absolutePath}")
        } catch (error: Throwable) {
            Log.e(TAG, "tts baseline failed", error)
            request?.let { validated ->
                writeJson(
                    debugTtsBaselineOutput(this, validated.lane, validated.label),
                    JSONObject()
                        .put("schema", DEBUG_TTS_BASELINE_SCHEMA)
                        .put("status", "failed")
                        .put("label", validated.label)
                        .put("lane", validated.lane)
                        .put("error", "${error::class.java.simpleName}: ${error.message}"),
                )
            }
        } finally {
            lane?.close()
        }
    }

    /** Lane factory. Each lane resolves its own voice and model inputs; unknown ids fail before any work. */
    private fun createLane(laneId: String): DebugTtsLane = when (laneId) {
        DebugTtsLanes.INFLECT_MICRO_V2 -> {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, DebugTtsBaselineEntryPoint::class.java)
            val voice = runBlocking { entryPoint.voiceOutputPreferences().selectedSherpaVoice.first() }
            val espeak = File(voice.voiceDir(this), ESPEAK_DATA_DIR)
            check(espeak.isDirectory) { "selected voice ${voice.name} has no $ESPEAK_DATA_DIR at ${espeak.path}" }
            val models = InflectMicroModelSpec.modelDirectory(this)
            val missing = InflectMicroModelSpec.requiredModels.firstOrNull { !File(models, it.fileName).isFile }
            check(missing == null) { "missing ${missing?.fileName} in ${models.path}" }
            InflectMicroDebugLane(models, voice.name, espeak)
        }
        else -> error("no lane factory for $laneId")
    }

    /**
     * Runs [block] on a daemon thread and fails after [deadlineMs]. A native call stuck past the deadline cannot be
     * interrupted; the thread is left behind and the caller writes the failure record.
     */
    private fun <T> withDeadline(deadlineMs: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "debug-tts-run").apply { isDaemon = true }
        }
        try {
            return executor.submit(Callable { block() }).get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw IllegalStateException("baseline run exceeded ${deadlineMs / 1000} s; a native call did not return", error)
        } finally {
            executor.shutdownNow()
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
        private const val KOKORO_ET_VOICE = "af_heart"

        /** True while a run owns the service. The receiver refuses overlapping runs using this. */
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        private const val TAG = "KernelAI"
        private const val CHANNEL_ID = "debug_tts_baseline"
        private const val NOTIFICATION_ID = 2088
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val RUN_DEADLINE_MS = 20L * 60 * 1000
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugTtsBaselineEntryPoint {
    fun voiceOutputPreferences(): VoiceOutputPreferences
}
