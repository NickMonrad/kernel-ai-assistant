package com.kernel.ai.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Defers stop requests until `startForeground()` has completed in `onStartCommand()`.
 */
internal class ForegroundServiceLifecycle {
    private val lock = Any()
    private var promotionPending = false
    private var stopAfterPromotion = false

    fun start(startService: () -> Unit) {
        synchronized(lock) {
            stopAfterPromotion = false
            if (promotionPending) return
            promotionPending = true
            try {
                startService()
            } catch (e: RuntimeException) {
                promotionPending = false
                stopAfterPromotion = false
                throw e
            }
        }
    }

    fun stop(stopService: () -> Unit) {
        synchronized(lock) {
            if (promotionPending) {
                stopAfterPromotion = true
                return
            }
            stopService()
        }
    }

    fun promoted(stopService: () -> Unit): Boolean = synchronized(lock) {
        promotionPending = false
        if (!stopAfterPromotion) return@synchronized false
        stopAfterPromotion = false
        stopService()
        true
    }
}

/**
 * Foreground service that keeps the process at high OOM priority during
 * on-device response generation.
 *
 * Without this, switching away from the app during generation could trigger
 * onTrimMemory at BACKGROUND+ levels and cancel the active generation.
 *
 * Start only when inference is ready to send a native request; stop when generation closes
 * (completion, error, or user cancellation).
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
        if (lifecycle.promoted { stopSelfResult(startId) }) {
            Log.i(TAG, "Generation service stopped after foreground promotion")
        } else {
            Log.i(TAG, "Generation foreground service started — process OOM priority elevated")
        }
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

        private val lifecycle = ForegroundServiceLifecycle()

        fun start(context: Context) {
            try {
                lifecycle.start {
                    context.startForegroundService(
                        Intent(context, InferenceGenerationService::class.java),
                    )
                }
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Foreground service start not allowed on this device state — " +
                    "generation may be killed if process is backgrounded", e)
            }
        }

        fun stop(context: Context) {
            lifecycle.stop {
                context.stopService(
                    Intent(context, InferenceGenerationService::class.java),
                )
            }
        }
    }
}
