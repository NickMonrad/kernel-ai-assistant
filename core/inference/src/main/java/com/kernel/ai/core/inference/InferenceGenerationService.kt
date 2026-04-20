package com.kernel.ai.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that keeps the process at high OOM priority during
 * on-device response generation.
 *
 * Without this, switching away from the app during generation triggers
 * onTrimMemory(TRIM_MEMORY_UI_HIDDEN=20), which fires releaseForMemoryPressure()
 * and cancels the active generation.
 *
 * Start at the beginning of [LiteRtInferenceEngine.generate], stop when the
 * flow closes (completion, error, or user cancellation).
 */
class InferenceGenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        Log.i(TAG, "Generation foreground service started — process OOM priority elevated")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Generation foreground service stopped")
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jandal is responding…")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Response",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while Jandal is generating a response" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "InferenceGenerationService"
        private const val CHANNEL_ID = "kernel_inference_generation"
        private const val NOTIFICATION_ID = 9002

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, InferenceGenerationService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, InferenceGenerationService::class.java),
            )
        }
    }
}
