package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceInputStartResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ALARM_SNOOZE_MS = 10 * 60 * 1_000L
private const val ALERT_ADD_MINUTE_MS = 60_000L
private const val AUTO_START_VOICE_DELAY_MS = 2_000L
internal fun shouldAutoStartAlertVoiceControl(
    enabled: Boolean,
    type: ClockEventType,
): Boolean = enabled && type != ClockEventType.PRE_ALARM



@AndroidEntryPoint
class ClockAlertService : Service() {
    @Inject lateinit var clockRepository: ClockRepository
    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var voiceInputPreferences: VoiceInputPreferences

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val vibratorManager: VibratorManager
        get() = getSystemService(VibratorManager::class.java)

    private val activeAlerts = linkedSetOf<TriggeredClockAlert>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voiceEventsJob: Job? = null
    private var voicePreferencesJob: Job? = null
    private var autoStartVoiceJob: Job? = null
    private var ringtone: Ringtone? = null
    private var isVoiceListening = false
    private var handledVoiceTranscript = false
    private var autoStartAlertVoiceCommandsEnabled = true
    private var voiceStatusMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        voiceEventsJob = serviceScope.launch {
            voiceInputController.events.collectLatest(::handleVoiceEvent)
        }
        voicePreferencesJob = serviceScope.launch {
            voiceInputPreferences.autoStartAlertVoiceCommandsEnabled.collectLatest { enabled ->
                autoStartAlertVoiceCommandsEnabled = enabled
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ClockAlertContract.ACTION_TRIGGER_ALERT -> {
                val alert = intent.toTriggeredClockAlert() ?: return START_NOT_STICKY
                activeAlerts.removeAll { it.ownerId == alert.ownerId }
                activeAlerts += alert
                isVoiceListening = false
                handledVoiceTranscript = false
                voiceStatusMessage = null
                voiceInputController.stopListening()
                ensureChannel()
                refreshForeground()
                startAlertPlayback()
                if (shouldAutoStartAlertVoiceControl(autoStartAlertVoiceCommandsEnabled, alert.type)) {
                    scheduleAutoStartVoiceControl(alert)
                }
            }

            ClockAlertContract.ACTION_STOP_ALERT -> {
                intent.toTriggeredClockAlert()?.let(::dismissAlert) ?: stopAlertSession()
            }

            ClockAlertContract.ACTION_SNOOZE_ALERT -> {
                val alert = intent.toTriggeredClockAlert()?.let { findActiveAlert(it.ownerId) } ?: currentAlert()
                if (alert != null) {
                    serviceScope.launch { performSnooze(alert, ALARM_SNOOZE_MS) }
                }
            }

            ClockAlertContract.ACTION_ADD_MINUTE_ALERT -> {
                val alert = intent.toTriggeredClockAlert()?.let { findActiveAlert(it.ownerId) } ?: currentAlert()
                if (alert != null) {
                    serviceScope.launch { performAddOneMinute(alert) }
                }
            }

            ClockAlertContract.ACTION_START_VOICE_CONTROL -> {
                val alert = intent.toTriggeredClockAlert()?.let { findActiveAlert(it.ownerId) } ?: currentAlert()
                if (alert != null) {
                    startVoiceControl(alert)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        voiceInputController.stopListening()
        voiceEventsJob?.cancel()
        voicePreferencesJob?.cancel()
        autoStartVoiceJob?.cancel()
        serviceScope.cancel()
        stopPlayback()
        super.onDestroy()
    }

    private fun buildNotification(alert: TriggeredClockAlert) =
        NotificationCompat.Builder(this, ClockAlertContract.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(
                if (activeAlerts.size > 1) "${activeAlerts.size} active alerts"
                else alert.title,
            )
            .setContentText(notificationContentText(alert))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(buildOpenAppPendingIntent())
            .setDeleteIntent(buildServicePendingIntent(ClockAlertContract.ACTION_STOP_ALERT, alert))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (alert.type == ClockEventType.TIMER) "Dismiss" else "Stop",
                buildServicePendingIntent(ClockAlertContract.ACTION_STOP_ALERT, alert),
            )
            .apply {
                when (alert.type) {
                    ClockEventType.ALARM -> addAction(
                        android.R.drawable.ic_lock_idle_alarm,
                        "Snooze",
                        buildServicePendingIntent(ClockAlertContract.ACTION_SNOOZE_ALERT, alert),
                    )

                    ClockEventType.TIMER -> addAction(
                        android.R.drawable.ic_menu_recent_history,
                        "+1 min",
                        buildServicePendingIntent(ClockAlertContract.ACTION_ADD_MINUTE_ALERT, alert),
                    )

                    ClockEventType.PRE_ALARM -> Unit
                }
                addAction(
                    android.R.drawable.ic_btn_speak_now,
                    "Voice",
                    buildServicePendingIntent(ClockAlertContract.ACTION_START_VOICE_CONTROL, alert),
                )
                if (notificationManager.canUseFullScreenIntent()) {
                    setFullScreenIntent(buildOpenAppPendingIntent(), true)
                }
            }
            .build()

    private fun notificationContentText(alert: TriggeredClockAlert): String =
        voiceStatusMessage ?: if (activeAlerts.size > 1) {
            "${alert.label} (+${activeAlerts.size - 1} more)"
        } else {
            alert.label
        }

    private fun refreshForeground() {
        val alert = currentAlert() ?: return
        startForeground(
            ClockAlertContract.ALERT_NOTIFICATION_ID,
            buildNotification(alert),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun startAlertPlayback() {
        if (isVoiceListening || activeAlerts.isEmpty()) return
        stopPlayback()
        startVibration()
        ringtone = RingtoneManager.getRingtone(
            this,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            isLooping = true
            play()
        }
    }

    private fun startVibration() {
        defaultVibrator()?.cancel()
        defaultVibrator()?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), 0),
        )
    }

    private fun stopAlertSession() {
        activeAlerts.clear()
        autoStartVoiceJob?.cancel()
        autoStartVoiceJob = null
        isVoiceListening = false
        handledVoiceTranscript = false
        voiceStatusMessage = null
        voiceInputController.stopListening()
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dismissAlert(alert: TriggeredClockAlert) {
        activeAlerts.removeAll { it.ownerId == alert.ownerId }
        cancelAutoStartVoiceControl(alert.ownerId)
        isVoiceListening = false
        handledVoiceTranscript = false
        voiceStatusMessage = null
        voiceInputController.stopListening()
        if (activeAlerts.isEmpty()) {
            stopAlertSession()
        } else {
            stopPlayback()
            refreshForeground()
            startAlertPlayback()
            currentAlert()
                ?.takeIf { shouldAutoStartAlertVoiceControl(autoStartAlertVoiceCommandsEnabled, it.type) }
                ?.let(::scheduleAutoStartVoiceControl)
        }
    }

    private fun stopPlayback() {
        ringtone?.stop()
        ringtone = null
        defaultVibrator()?.cancel()
    }

    private fun defaultVibrator(): Vibrator? = vibratorManager.defaultVibrator

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(ClockAlertContract.ALERT_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ClockAlertContract.ALERT_CHANNEL_ID,
                "Clock alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm and timer completion alerts"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun buildOpenAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildServicePendingIntent(action: String, alert: TriggeredClockAlert): PendingIntent =
        PendingIntent.getService(
            this,
            31 * action.hashCode() + alert.ownerId.hashCode(),
            Intent(this, ClockAlertService::class.java).apply {
                this.action = action
                putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
                putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
                putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
                putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
                putExtra(
                    ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
                    alert.occurrenceTriggerAtMillis ?: -1L,
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleAutoStartVoiceControl(alert: TriggeredClockAlert) {
        cancelAutoStartVoiceControl()
        autoStartVoiceJob = serviceScope.launch {
            kotlinx.coroutines.delay(AUTO_START_VOICE_DELAY_MS)
            val current = findActiveAlert(alert.ownerId) ?: return@launch
            if (!shouldAutoStartAlertVoiceControl(autoStartAlertVoiceCommandsEnabled, current.type) || isVoiceListening) return@launch
            startVoiceControl(current, autoStarted = true)
        }
    }

    private fun cancelAutoStartVoiceControl(ownerId: String? = null) {
        val shouldCancel = ownerId == null || findActiveAlert(ownerId) == null
        if (shouldCancel) {
            autoStartVoiceJob?.cancel()
            autoStartVoiceJob = null
        }
    }


    private fun currentAlert(): TriggeredClockAlert? = activeAlerts.lastOrNull()

    private fun findActiveAlert(ownerId: String): TriggeredClockAlert? =
        activeAlerts.firstOrNull { it.ownerId == ownerId }

    private fun startVoiceControl(alert: TriggeredClockAlert, autoStarted: Boolean = false) {
        if (isVoiceListening) return
        cancelAutoStartVoiceControl()
        isVoiceListening = true
        handledVoiceTranscript = false
        voiceStatusMessage = if (autoStarted) "Listening for alert commands…" else alertVoiceListeningPrompt(alert.type)
        refreshForeground()
        serviceScope.launch {
            when (val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)) {
                VoiceInputStartResult.Started -> Unit
                is VoiceInputStartResult.Unavailable -> finishVoiceCapture(
                    result.message.ifBlank { "Voice commands are unavailable right now." },
                )
            }
        }
    }

    private suspend fun handleVoiceEvent(event: VoiceInputEvent) {
        if (!isVoiceListening) return
        when (event) {
            is VoiceInputEvent.ListeningStarted -> {
                currentAlert()?.let { voiceStatusMessage = alertVoiceListeningPrompt(it.type) }
                refreshForeground()
            }

            is VoiceInputEvent.PartialTranscript -> Unit

            is VoiceInputEvent.Transcript -> {
                handledVoiceTranscript = true
                val alert = currentAlert() ?: return finishVoiceCapture("No active alert to control.")
                handleVoiceTranscript(alert, event.text)
            }

            is VoiceInputEvent.Error -> {
                handledVoiceTranscript = true
                finishVoiceCapture(event.message)
            }

            is VoiceInputEvent.ListeningStopped -> {
                if (!handledVoiceTranscript) {
                    finishVoiceCapture("I didn't catch a supported alert command.")
                }
            }
        }
    }

    private suspend fun handleVoiceTranscript(alert: TriggeredClockAlert, transcript: String) {
        isVoiceListening = false
        val command = parseClockAlertVoiceCommand(transcript)
            ?: return finishVoiceCapture("Say stop, dismiss, snooze, or add one minute.")
        alertVoiceUnsupportedMessage(command, alert.type)?.let { message ->
            return finishVoiceCapture(message)
        }
        when (command) {
            ClockAlertVoiceCommand.DISMISS -> dismissAlert(alert)
            ClockAlertVoiceCommand.SNOOZE -> performSnooze(alert, ALARM_SNOOZE_MS)
            ClockAlertVoiceCommand.ADD_ONE_MINUTE -> performAddOneMinute(alert)
        }
    }

    private suspend fun performSnooze(alert: TriggeredClockAlert, durationMs: Long) {
        if (alert.type != ClockEventType.ALARM) {
            finishVoiceCapture("Snooze is only available for alarms.")
            return
        }
        val success = clockRepository.snoozeAlarm(
            alarmId = alert.ownerId,
            snoozedUntilMillis = System.currentTimeMillis() + durationMs,
        )
        if (success) {
            dismissAlert(alert)
        } else {
            finishVoiceCapture("Couldn't snooze the alarm.")
        }
    }

    private suspend fun performAddOneMinute(alert: TriggeredClockAlert) {
        val success = when (alert.type) {
            ClockEventType.ALARM -> clockRepository.snoozeAlarm(
                alarmId = alert.ownerId,
                snoozedUntilMillis = System.currentTimeMillis() + ALERT_ADD_MINUTE_MS,
            )

            ClockEventType.TIMER -> clockRepository.scheduleTimer(
                durationMs = ALERT_ADD_MINUTE_MS,
                label = alert.label.takeIf { it.isNotBlank() },
            ) != null

            ClockEventType.PRE_ALARM -> false
        }
        if (success) {
            dismissAlert(alert)
        } else {
            finishVoiceCapture("Couldn't add one minute.")
        }
    }

    private fun finishVoiceCapture(message: String) {
        isVoiceListening = false
        handledVoiceTranscript = false
        voiceStatusMessage = message
        voiceInputController.stopListening()
        if (activeAlerts.isEmpty()) {
            stopAlertSession()
        } else {
            refreshForeground()
            startAlertPlayback()
        }
    }

    private fun Intent.toTriggeredClockAlert(): TriggeredClockAlert? {
        val ownerId = getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return null
        val label = getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: return null
        val title = getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: return null
        val type = getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
            ?.let(ClockEventType::valueOf)
            ?: ClockEventType.ALARM
        val occurrenceTriggerAtMillis = getLongExtra(
            ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
            -1L,
        ).takeIf { it > 0L }
        return TriggeredClockAlert(
            ownerId = ownerId,
            type = type,
            title = title,
            label = label,
            occurrenceTriggerAtMillis = occurrenceTriggerAtMillis,
        )
    }

    companion object {
        internal fun trigger(context: Context, alert: TriggeredClockAlert) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClockAlertService::class.java).apply {
                    action = ClockAlertContract.ACTION_TRIGGER_ALERT
                    putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
                    putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
                    putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
                    putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
                    putExtra(
                        ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
                        alert.occurrenceTriggerAtMillis ?: -1L,
                    )
                },
            )
        }
    }
}
