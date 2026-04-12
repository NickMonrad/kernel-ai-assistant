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
 * GPU model initialisation.
 *
 * Without this, Samsung's lmkd demotes the app to oom_score_adj 700 (cached)
 * during the ~20s GPU init, then kills it because of memory watermark pressure
 * from the 1-3GB GPU allocation.
 *
 * Start before [LiteRtInferenceEngine.initialize], stop after it returns.
 */
class InferenceLoadingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        Log.i(TAG, "Foreground inference service started — process OOM priority elevated")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Foreground inference service stopped")
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Loading AI model…")
            .setContentText("Preparing on-device inference engine")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Loading",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the AI model initialises on GPU" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "InferenceLoadingService"
        private const val CHANNEL_ID = "kernel_inference_loading"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, InferenceLoadingService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, InferenceLoadingService::class.java),
            )
        }
    }
}
