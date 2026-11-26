package com.kernel.ai.core.inference

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Defers stop requests until `startForeground()` has completed in `onStartCommand()`.
 */
class ForegroundServiceLifecycle {
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

/** Cancels an action scheduled with a delay. */
internal fun interface CancelDelayed {
    fun cancel()
}

/**
 * Decides when the generation service stops (#2000).
 *
 * A generation that ends with the screen off or locked, and whose reply will be spoken, keeps the service
 * promoted for [lingerMs]. That covers the gap before speech starts; the speech itself is foreground-scheduled
 * by SpeechPlaybackService (mediaPlayback), not by this service. Any other end stops the service at once, and
 * a new generation cancels a pending linger. The linger is the only time-based hold, so a stuck speech state
 * cannot keep this service up.
 */
internal class ReplyLingerGate(
    private val lingerMs: Long,
    private val isScreenOffOrLocked: () -> Boolean,
    private val schedule: (delayMs: Long, action: () -> Unit) -> CancelDelayed,
    private val stopService: () -> Unit,
) {
    private val lock = Any()
    private var generationRunning = false
    private var pendingLinger: CancelDelayed? = null

    /** True while a linger is scheduled. */
    val isLingering: Boolean
        get() = synchronized(lock) { pendingLinger != null }

    fun onGenerationStart() {
        synchronized(lock) {
            generationRunning = true
            cancelLinger()
        }
    }

    /** Ends a generation. [lingerForReply] is true only for a reply that will be spoken. */
    fun onGenerationEnd(lingerForReply: Boolean) {
        synchronized(lock) {
            generationRunning = false
            cancelLinger()
            if (!lingerForReply || !isScreenOffOrLocked()) {
                stopService()
            } else {
                pendingLinger = schedule(lingerMs) {
                    synchronized(lock) {
                        pendingLinger = null
                        if (!generationRunning) stopService()
                    }
                }
            }
        }
    }

    private fun cancelLinger() {
        pendingLinger?.cancel()
        pendingLinger = null
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

        /**
         * How long the promoted service outlives a spoken reply that ends with the screen off or locked (#2000),
         * so speech that starts shortly after generation still runs at foreground priority.
         */
        private const val REPLY_LINGER_MS = 30_000L

        private val lifecycle = ForegroundServiceLifecycle()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        @Volatile private var appContext: Context? = null

        private val gate = ReplyLingerGate(
            lingerMs = REPLY_LINGER_MS,
            isScreenOffOrLocked = { isScreenOffOrLocked(checkNotNull(appContext)) },
            schedule = { delayMs, action ->
                val runnable = Runnable(action)
                mainHandler.postDelayed(runnable, delayMs)
                CancelDelayed { mainHandler.removeCallbacks(runnable) }
            },
            stopService = { stopNow(checkNotNull(appContext)) },
        )

        fun start(context: Context) {
            gate.onGenerationStart()
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

        /** Ends the generation's use of the service now: cancel, tool abort, internal generation. */
        fun stop(context: Context) {
            appContext = context.applicationContext
            gate.onGenerationEnd(lingerForReply = false)
        }

        /**
         * Ends a generation whose reply will be spoken. With the screen off or locked the service stays promoted
         * for [REPLY_LINGER_MS] unless another generation starts (#2000).
         */
        fun stopAfterReply(context: Context) {
            appContext = context.applicationContext
            gate.onGenerationEnd(lingerForReply = true)
            if (gate.isLingering) Log.i(TAG, "Reply ended with screen off or locked; service kept promoted for ${REPLY_LINGER_MS}ms")
        }

        private fun isScreenOffOrLocked(context: Context): Boolean {
            val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
            val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            return !interactive || locked
        }

        private fun stopNow(context: Context) {
            lifecycle.stop {
                context.stopService(
                    Intent(context, InferenceGenerationService::class.java),
                )
            }
        }
    }
}
