package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import com.kernel.ai.assistant.WakeSessionJournal
import com.kernel.ai.assistant.cueMetadata
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.StartListeningCueResult
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.SchedulingResult
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.memory.clock.ClockAlertConfig
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.startListening
import com.kernel.ai.core.voice.noticesFor
import com.kernel.ai.core.voice.stopListening
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure predicate: whether an event should be handled by the clock-alert voice session.
 * Does not depend on Android framework state.
 */
internal fun isOwnedAlertEvent(
    event: VoiceInputEvent,
    captureSessionId: Long?,
    isVoiceListening: Boolean,
): Boolean = isVoiceListening && event.captureSessionId != null && event.captureSessionId == captureSessionId

/**
 * Whether [attemptJournal] is still the active clock‑alert voice journal.
 * Used by [startVoiceControl] before processing a startup result — a stale
 * attempt must not mutate shared service state if a replacement session has
 * already taken over.
 */
internal fun ownsCurrentVoiceJournal(
    currentJournal: WakeSessionJournal?,
    attemptJournal: WakeSessionJournal,
): Boolean = currentJournal === attemptJournal

/**
 * Runs a snooze operation and dismisses the current alert only on success.
 * Used by both the notification Snooze button and the voice Snooze command.
 * Tests verify the orchestration: snooze is invoked exactly once, dismiss
 * only when snooze returns true.
 */
internal suspend fun runSnoozeAction(
    snooze: suspend () -> Boolean,
    dismiss: () -> Unit,
    shouldPreserveVoiceSession: () -> Boolean = { false },
    dismissPreservingVoiceSession: () -> Unit = dismiss,
) {
    if (snooze()) {
        if (shouldPreserveVoiceSession()) dismissPreservingVoiceSession() else dismiss()
    }
}

internal suspend fun runAutoSnoozeAction(
    snooze: suspend () -> Boolean,
    dismiss: (preserveVoiceSession: Boolean) -> Unit,
    shouldPreserveVoiceSession: () -> Boolean,
): Boolean {
    val success = snooze()
    dismiss(shouldPreserveVoiceSession())
    return success
}

/**
 * Whether a voice event should trigger the clock-alert start-listening cue.
 * Used by both [ClockAlertService.handleVoiceEvent] and tests.
 */
internal fun shouldPlayClockAlertListeningCue(
    event: VoiceInputEvent,
    captureSessionId: Long?,
    isVoiceListening: Boolean,
): Boolean =
    isOwnedAlertEvent(event, captureSessionId, isVoiceListening) &&
        event is VoiceInputEvent.ListeningStarted &&
        event.mode == VoiceCaptureMode.AlertCommand

/**
 * Returns the user-facing message for an unavailable voice controller,
 * falling back to a generic message when the controller provides none.
 */
internal fun alertVoiceUnavailableMessage(message: String?): String =
    message?.takeIf { it.isNotBlank() }
        ?: "Voice commands are unavailable right now."

/**
 * Record cue-journal evidence for a clock-alert listening attempt.
 */
internal fun recordClockAlertCue(
    journal: WakeSessionJournal,
    cuePlayer: StartListeningCuePlayer,
): StartListeningCueResult {
    journal.record(
        AcousticEventType.CUE_REQUESTED,
        metadata = {
            mapOf(
                "context" to "clock_alert",
                "policy_version" to "2026-07-cue-v1",
            )
        },
    )
    val cueResult = cuePlayer.playCue(StartListeningCueContext.CLOCK_ALERT)
    if (cueResult.started) {
        journal.record(
            AcousticEventType.CUE_PLAYBACK_STARTED,
            metadata = { cueMetadata(cueResult, context = "clock_alert") },
        )
    } else {
        journal.record(
            AcousticEventType.CUE_PLAYBACK_ERROR,
            metadata = { cueMetadata(cueResult, context = "clock_alert", isError = true) },
        )
    }
    return cueResult
}

/**
 * Terminalise the clock-alert voice journal with exactly one terminal event.
 * Returns null so callers can atomically clear [voiceJournal]: `voiceJournal = terminaliseClockAlertVoiceJournal(...)`.
 */
internal fun terminaliseClockAlertVoiceJournal(
    journal: WakeSessionJournal?,
    completed: Boolean,
    cancellationCategory: String = "",
): WakeSessionJournal? {
    journal?.let {
        if (completed) {
            it.complete()
        } else {
            it.cancel(cancellationCategory)
        }
    }
    return null
}


private const val TAG = "KernelAI"
private const val ALARM_SNOOZE_MS = 10 * 60 * 1_000L
private const val ALERT_ADD_MINUTE_MS = 60_000L
private const val AUTO_START_VOICE_DELAY_MS = 2_000L
private const val ALERT_PLAYBACK_FULL_VOLUME = 1.0f
private const val ALERT_PLAYBACK_DUCKED_VOLUME = 0.2f
internal fun shouldAutoStartAlertVoiceControl(
    enabled: Boolean,
    type: ClockEventType,
): Boolean = enabled && type != ClockEventType.PRE_ALARM

internal fun shouldDuckAlertPlayback(activeAlertsSize: Int): Boolean = activeAlertsSize > 0

internal fun shouldRestoreDuckedPlayback(
    duckingPlayback: Boolean,
    activeAlertsSize: Int,
): Boolean = duckingPlayback && activeAlertsSize > 0

internal fun applyAlertPlaybackVolume(ringtone: Ringtone, volume: Float) {
    ringtone.setVolume(volume)
}

internal fun alertPendingIntentIdentity(action: String, alert: TriggeredClockAlert): String =
    "kernel-ai://clock-alert/${action}|${alert.type.name.lowercase()}|${alert.ownerId}|" +
        (alert.occurrenceTriggerAtMillis?.toString() ?: "none")

internal fun configuredSnoozeDurationMs(
    configuredMs: Long,
    fallbackMs: Long = ALARM_SNOOZE_MS,
): Long = configuredMs.takeIf { it > 0L } ?: fallbackMs

internal class ClockAlertVoiceCaptureOwner {
    private var sessionId: Long? = null

    fun bind(captureSessionId: Long) {
        sessionId = captureSessionId
    }

    fun clear(captureSessionId: Long) {
        if (sessionId == captureSessionId) sessionId = null
    }

    fun stop(stopSession: (Long) -> Unit) {
        val ownedSessionId = sessionId ?: return
        stopSession(ownedSessionId)
        clear(ownedSessionId)
    }
}

@AndroidEntryPoint
class ClockAlertService : Service() {
    @Inject lateinit var startListeningCuePlayer: StartListeningCuePlayer
    @Inject lateinit var clockRepository: ClockRepository
    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var voiceInputPreferences: VoiceInputPreferences

    private var captureSessionId: Long? = null
    private val voiceCaptureOwner = ClockAlertVoiceCaptureOwner()
    private val voiceAttemptIds = AtomicLong(0L)
    private var voiceJournal: WakeSessionJournal? = null
    private val activeAlerts = linkedSetOf<TriggeredClockAlert>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voiceEventsJob: Job? = null
    /** Collects notes for the current alert capture (#1853 Zipformer hand-off, #1866). */
    private var voiceNoticeJob: Job? = null
    /** Latest note for the current alert capture, shown before the listening prompt. */
    private var voiceNotice: String? = null
    private var voicePreferencesJob: Job? = null

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val vibratorManager: VibratorManager
        get() = getSystemService(VibratorManager::class.java)
    private var clockSoundConfigJob: Job? = null
    private var clockAlertConfigJob: Job? = null
    private var autoStartVoiceJob: Job? = null
    private var voiceStartJob: Job? = null
    private var ringtone: Ringtone? = null
    private var duckingPlayback = false
    private var isVoiceListening = false
    private var handledVoiceTranscript = false
    private var autoStartAlertVoiceCommandsEnabled = true
    private var defaultAlarmSoundUri: String? = null
    private var timerSoundUri: String? = null
    private var timerAutoStopDurationMs: Long = 60_000L
    private var alarmRingDurationMs: Long = 60_000L
    private var snoozeDurationMs: Long = 600_000L
    private var maxAutoSnoozes: Int = 1
    private var voiceStatusMessage: String? = null
    /** Config captured per active alert, keyed by ownerId. */
    private val activeAlertConfigs = mutableMapOf<String, ClockAlertConfig>()
    /** Per-alert lifecycle timeout jobs, keyed by ownerId. */
    private val lifecycleJobs = mutableMapOf<String, Job>()

    override fun onCreate() {
        super.onCreate()
        voiceEventsJob = serviceScope.launch {
            voiceInputController.events.collect(::handleVoiceEvent)
        }
        voicePreferencesJob = serviceScope.launch {
            voiceInputPreferences.autoStartAlertVoiceCommandsEnabled.collectLatest { enabled ->
                autoStartAlertVoiceCommandsEnabled = enabled
            }
        }
        clockSoundConfigJob = serviceScope.launch {
            clockRepository.observeClockSoundConfig().collectLatest { config ->
                defaultAlarmSoundUri = config.defaultAlarmSoundUri
                timerSoundUri = config.timerSoundUri
            }
        }
        clockAlertConfigJob = serviceScope.launch {
            clockRepository.observeClockAlertConfig().collectLatest { config ->
                timerAutoStopDurationMs = config.timerAutoStopDurationMs
                alarmRingDurationMs = config.alarmRingDurationMs
                snoozeDurationMs = config.snoozeDurationMs
                maxAutoSnoozes = config.maxAutoSnoozes
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ClockAlertContract.ACTION_TRIGGER_ALERT -> {
                serviceScope.launch { handleTriggerAlert(intent) }
            }

            ClockAlertContract.ACTION_STOP_ALERT -> {
                intent.toTriggeredClockAlert()?.let { alert ->
                    dismissAlert(alert)
                } ?: stopAlertSession()
            }

            ClockAlertContract.ACTION_STOP_TIMER_ALERTS -> {
                val dismissed = dismissAlertsMatching(predicate = { it.type == ClockEventType.TIMER })
                if (dismissed == 0 && activeAlerts.isEmpty()) stopSelf()
            }
            ClockAlertContract.ACTION_SNOOZE_ALERT -> {
                val alert = intent.toTriggeredClockAlert()?.let { findActiveAlert(it.ownerId) } ?: currentAlert()
                if (alert != null) {
                    val attemptJournal = voiceJournal
                    serviceScope.launch {
                        runSnoozeAction(
                            snooze = {
                                performSnooze(
                                    alert,
                                    snoozeDurationFor(alert),
                                )
                            },
                            dismiss = { dismissAlert(alert) },
                            shouldPreserveVoiceSession = { voiceJournal !== attemptJournal },
                            dismissPreservingVoiceSession = {
                                dismissAlert(alert, preserveVoiceSession = true)
                            },
                        )
                    }
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


    /**
     * Handles an incoming trigger alert intent. Loads the persisted clock alert
     * config upfront so the lifecycle timeout uses the user's configured values
     * rather than racing the async DataStore collector in [onCreate].
     *
     * The config is captured once per trigger and used throughout this alert's
     * lifecycle; it does not live-update mid-ring.
     */
    private suspend fun handleTriggerAlert(intent: Intent?) {
        val alert = intent?.toTriggeredClockAlert() ?: return
        val config = clockRepository.getClockAlertConfig()
        activeAlertConfigs[alert.ownerId] = config
        val autoSnoozeCount = alert.autoSnoozeCount
        withContext(Dispatchers.Main.immediate) {
            cancelLifecycleTimeout(alert.ownerId)
            activeAlerts.removeAll { it.ownerId == alert.ownerId }
            activeAlerts += alert
            syncActiveAlertSnapshot()
            isVoiceListening = false
            handledVoiceTranscript = false
            voiceStatusMessage = null
            stopOwnedVoiceCapture()
            ensureChannel()
            refreshForeground()
            startAlertPlayback()
            scheduleLifecycleTimeout(alert, autoSnoozeCount, config)
            if (shouldAutoStartAlertVoiceControl(autoStartAlertVoiceCommandsEnabled, alert.type)) {
                scheduleAutoStartVoiceControl(alert)
            }
        }
    }
    override fun onDestroy() {
        voiceJournal = terminaliseClockAlertVoiceJournal(voiceJournal, completed = false, "service_stopped")
        captureSessionId = null
        stopOwnedVoiceCapture()
        voiceEventsJob?.cancel()
        voiceNoticeJob?.cancel()
        voicePreferencesJob?.cancel()
        clockSoundConfigJob?.cancel()
        clockAlertConfigJob?.cancel()
        autoStartVoiceJob?.cancel()
        lifecycleJobs.values.forEach { it.cancel() }
        lifecycleJobs.clear()
        serviceScope.cancel()
        stopPlayback()
        activeAlerts.clear()
        activeAlertConfigs.clear()
        syncActiveAlertSnapshot()
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

    private fun startAlertPlayback(ducked: Boolean = false) {
        val alert = currentAlert() ?: return
        stopPlayback()
        if (ducked) {
            defaultVibrator()?.cancel()
        } else {
            startVibration()
        }
        val soundUri = resolveAlertSoundUri(alert)
        ringtone = RingtoneManager.getRingtone(this, soundUri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            isLooping = true
            applyAlertPlaybackVolume(
                this,
                if (ducked) ALERT_PLAYBACK_DUCKED_VOLUME else ALERT_PLAYBACK_FULL_VOLUME,
            )
            play()
        }
        duckingPlayback = ducked && ringtone != null
    }

    private fun resolveAlertSoundUri(alert: TriggeredClockAlert): Uri =
        Uri.parse(
            when (alert.type) {
                ClockEventType.ALARM -> alert.soundUri ?: defaultAlarmSoundUri
                ClockEventType.TIMER -> timerSoundUri
                ClockEventType.PRE_ALARM -> alert.soundUri ?: defaultAlarmSoundUri
            } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString(),
        )

    private fun startVibration() {
        defaultVibrator()?.cancel()
        defaultVibrator()?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), 0),
        )
    }
    private fun stopAlertSession() {
        voiceJournal = terminaliseClockAlertVoiceJournal(voiceJournal, completed = false, "service_stopped")
        lifecycleJobs.values.forEach { it.cancel() }
        lifecycleJobs.clear()
        activeAlerts.clear()
        activeAlertConfigs.clear()
        syncActiveAlertSnapshot()
        autoStartVoiceJob?.cancel()
        autoStartVoiceJob = null
        isVoiceListening = false
        handledVoiceTranscript = false
        voiceStatusMessage = null
        stopOwnedVoiceCapture()
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopOwnedVoiceCapture() {
        voiceStartJob?.cancel()
        voiceStartJob = null
        voiceCaptureOwner.stop(voiceInputController::stopListening)
        captureSessionId = null
    }

    private fun dismissAlert(
        alert: TriggeredClockAlert,
        preserveVoiceSession: Boolean = false,
    ) {
        dismissAlertsMatching({ it.ownerId == alert.ownerId }, preserveVoiceSession)
    }

    private fun dismissAlertsMatching(
        predicate: (TriggeredClockAlert) -> Boolean,
        preserveVoiceSession: Boolean = false,
    ): Int {
        val toDismiss = activeAlerts.filter(predicate)
        if (toDismiss.isEmpty()) return 0

        if (!preserveVoiceSession) {
            voiceJournal = terminaliseClockAlertVoiceJournal(voiceJournal, completed = false, "alert_dismissed_externally")
            isVoiceListening = false
            handledVoiceTranscript = false
            voiceStatusMessage = null
            stopOwnedVoiceCapture()
        }
        toDismiss.forEach {
            cancelLifecycleTimeout(it.ownerId)
            activeAlertConfigs.remove(it.ownerId)
        }
        activeAlerts.removeAll(predicate)
        syncActiveAlertSnapshot()
        if (!preserveVoiceSession) cancelAutoStartVoiceControl()
        if (preserveVoiceSession) {
            if (activeAlerts.isEmpty()) stopPlayback() else refreshForeground()
            return toDismiss.size
        }
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
        return toDismiss.size
    }

    private fun stopPlayback() {
        ringtone?.stop()
        ringtone = null
        duckingPlayback = false
        defaultVibrator()?.cancel()
    }

    private fun duckPlaybackForVoiceControl() {
        if (shouldDuckAlertPlayback(activeAlerts.size)) {
            startAlertPlayback(ducked = true)
        }
    }

    private fun restorePlaybackAfterVoiceCapture() {
        if (shouldRestoreDuckedPlayback(duckingPlayback, activeAlerts.size)) {
            startAlertPlayback()
        } else if (activeAlerts.isNotEmpty() && ringtone?.isPlaying != true) {
            startAlertPlayback()
        }
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
            0,
            Intent(this, ClockAlertService::class.java).apply {
                this.action = action
                data = Uri.parse(alertPendingIntentIdentity(action, alert))
                putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
                putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
                putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
                putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
                putExtra(
                    ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
                    alert.occurrenceTriggerAtMillis ?: -1L,
                )
                putExtra(ClockAlertContract.EXTRA_SOUND_URI, alert.soundUri)
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

    private fun syncActiveAlertSnapshot() {
        activeAlertSnapshot = activeAlerts.toSet()
    }

    /** Resolves the snooze duration for an alert from its captured config,
     *  falling back to the mutable field and then to the hardcoded default. */
    private fun snoozeDurationFor(alert: TriggeredClockAlert): Long {
        val configured = activeAlertConfigs[alert.ownerId]?.snoozeDurationMs ?: snoozeDurationMs
        return configuredSnoozeDurationMs(configured)
    }

    // ── Lifecycle timeout management ──────────────────────────────────

    /** Schedules a coroutine that fires after the ringing timeout for this alert.
     *  When [config] is provided, its values are used for the timeout duration
     *  and lifecycle decision; otherwise the service's mutable fields are used
     *  (which may be default values if the DataStore collector hasn't emitted yet). */
    private fun scheduleLifecycleTimeout(
        alert: TriggeredClockAlert,
        autoSnoozeCount: Int,
        config: ClockAlertConfig? = null,
    ) {
        cancelLifecycleTimeout(alert.ownerId)
        val maxSnoozes = config?.maxAutoSnoozes ?: maxAutoSnoozes
        val timerDur = config?.timerAutoStopDurationMs ?: timerAutoStopDurationMs
        val alarmDur = config?.alarmRingDurationMs ?: alarmRingDurationMs
        val snoozeMs = config?.snoozeDurationMs ?: snoozeDurationMs
        val timeoutMs = lifecycleTimeoutDurationMs(alert.type, autoSnoozeCount, maxSnoozes, timerDur, alarmDur)
        if (timeoutMs <= 0L) return
        val job = serviceScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            val activeAlert = findActiveAlert(alert.ownerId) ?: return@launch
            handleLifecycleTimeout(activeAlert, autoSnoozeCount, maxSnoozes, snoozeMs)
        }
        lifecycleJobs[alert.ownerId] = job
    }

    private fun cancelLifecycleTimeout(ownerId: String) {
        lifecycleJobs[ownerId]?.cancel()
        lifecycleJobs.remove(ownerId)
    }
    private suspend fun handleLifecycleTimeout(
        alert: TriggeredClockAlert,
        autoSnoozeCount: Int,
        maxSnoozes: Int = maxAutoSnoozes,
        snoozeMs: Long = snoozeDurationMs,
    ) {
        when (resolveAlertLifecycleAction(alert.type, autoSnoozeCount, maxSnoozes)) {
            ClockAlertLifecycleAction.AUTO_STOP -> performAutoStop(alert)
            ClockAlertLifecycleAction.AUTO_SNOOZE -> performAutoSnooze(alert, snoozeMs)
            null -> Unit
        }
    }
    private fun startVoiceControl(alert: TriggeredClockAlert, autoStarted: Boolean = false) {
        stopOwnedVoiceCapture()
        voiceJournal = terminaliseClockAlertVoiceJournal(voiceJournal, completed = false, "voice_session_replaced")
        isVoiceListening = true
        handledVoiceTranscript = false
        captureSessionId = null
        clearVoiceNotice()
        voiceStatusMessage = if (autoStarted) "Listening for alert commands…" else alertVoiceListeningPrompt(alert.type)
        duckPlaybackForVoiceControl()
        refreshForeground()

        val attemptJournal = WakeSessionJournal(
            generationId = 0L,
            sessionId = voiceAttemptIds.incrementAndGet(),
        )
        attemptJournal.start()
        voiceJournal = attemptJournal

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (
                    val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand) { sessionId ->
                        if (voiceJournal === attemptJournal) {
                            captureSessionId = sessionId
                            voiceCaptureOwner.bind(sessionId)
                            watchVoiceNotices(sessionId)
                        }
                    }
                ) {
                    is VoiceInputStartResult.Started -> {
                        if (!ownsCurrentVoiceJournal(voiceJournal, attemptJournal)) return@launch
                        captureSessionId = result.captureSessionId
                        voiceCaptureOwner.bind(result.captureSessionId)
                    }
                    is VoiceInputStartResult.Unavailable -> {
                        if (voiceJournal === attemptJournal) {
                            finishVoiceCapture(
                                alertVoiceUnavailableMessage(result.message),
                                "stt_unavailable",
                                expectedJournal = attemptJournal,
                            )
                        }
                    }
                    is VoiceInputStartResult.Cancelled -> {
                        val sessionId = result.captureSessionId
                        if (sessionId == null) cleanupCancelledAlertAttempt(attemptJournal)
                        else cleanupCancelledAlertSession(attemptJournal, sessionId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ClockAlertService: voice startup failed", e)
                if (voiceJournal === attemptJournal) {
                    finishVoiceCapture(
                        "Voice command startup failed.",
                        "voice_startup_failed",
                        expectedJournal = attemptJournal,
                    )
                }
            }
        }
        voiceStartJob = job
        job.start()
    }
    private suspend fun handleVoiceEvent(event: VoiceInputEvent) {
        val ownerJournal = voiceJournal ?: return
        val ownerSessionId = captureSessionId ?: return
        if (!isOwnedAlertEvent(event, ownerSessionId, isVoiceListening)) return
        when (event) {
            is VoiceInputEvent.ListeningStarted -> {
                if (shouldPlayClockAlertListeningCue(event, ownerSessionId, isVoiceListening)) {
                    ownerJournal.record(AcousticEventType.STT_READY)
                    recordClockAlertCue(ownerJournal, startListeningCuePlayer)
                    currentAlert()?.let { voiceStatusMessage = listeningStatus(it) }
                    refreshForeground()
                }
            }
            is VoiceInputEvent.SpeechDetected -> Unit
            is VoiceInputEvent.SpeechEnded -> Unit
            is VoiceInputEvent.PartialTranscript -> Unit
            is VoiceInputEvent.Transcript -> {
                captureSessionId = null
                handledVoiceTranscript = true
                val alert = currentAlert() ?: return finishVoiceCapture(
                    "No active alert to control.",
                    "command_execution_failed",
                    expectedJournal = ownerJournal,
                    ownedSessionId = ownerSessionId,
                )
                handleVoiceTranscript(alert, event.text, ownerJournal, ownerSessionId)
            }
            is VoiceInputEvent.Error -> {
                captureSessionId = null
                handledVoiceTranscript = true
                finishVoiceCapture(
                    event.message,
                    "stt_recognition_failed",
                    expectedJournal = ownerJournal,
                    ownedSessionId = ownerSessionId,
                )
            }
            is VoiceInputEvent.ListeningStopped -> {
                if (event.cancelled) {
                    cleanupCancelledAlertSession(ownerJournal, event.captureSessionId)
                } else if (!handledVoiceTranscript) {
                    captureSessionId = null
                    finishVoiceCapture(
                        "I didn't catch a supported alert command.",
                        "stt_stopped_without_result",
                        expectedJournal = ownerJournal,
                        ownedSessionId = ownerSessionId,
                    )
                }
            }
        }
    }
    private fun cleanupCancelledAlertAttempt(attemptJournal: WakeSessionJournal) {
        if (!ownsCurrentVoiceJournal(voiceJournal, attemptJournal)) return
        isVoiceListening = false
        clearVoiceNotice()
        captureSessionId = null
        handledVoiceTranscript = false
        voiceJournal = terminaliseClockAlertVoiceJournal(attemptJournal, completed = false, "voice_start_cancelled")
        if (activeAlerts.isNotEmpty()) {
            voiceStatusMessage = "Voice command cancelled."
            refreshForeground()
            restorePlaybackAfterVoiceCapture()
        }
    }
    private fun cleanupCancelledAlertSession(attemptJournal: WakeSessionJournal, sessionId: Long) {
        if (voiceJournal !== attemptJournal || captureSessionId != sessionId) return
        captureSessionId = null
        voiceCaptureOwner.clear(sessionId)
        isVoiceListening = false
        handledVoiceTranscript = false
        clearVoiceNotice()
        voiceJournal = terminaliseClockAlertVoiceJournal(voiceJournal, completed = false, "voice_start_cancelled")
        if (activeAlerts.isNotEmpty()) {
            voiceStatusMessage = "Voice command cancelled."
            refreshForeground()
            restorePlaybackAfterVoiceCapture()
        }
    }
    private suspend fun handleVoiceTranscript(
        alert: TriggeredClockAlert,
        transcript: String,
        expectedJournal: WakeSessionJournal,
        ownedSessionId: Long,
    ) {
        if (voiceJournal !== expectedJournal) return
        isVoiceListening = false
        val command = parseClockAlertVoiceCommand(transcript)
            ?: return finishVoiceCapture(
                "Say stop, dismiss, snooze, or add one minute.",
                "unsupported_alert_command",
                expectedJournal,
                ownedSessionId,
            )
        alertVoiceUnsupportedMessage(command, alert.type)?.let { message ->
            return finishVoiceCapture(message, "unsupported_alert_command", expectedJournal, ownedSessionId)
        }
        when (command) {
            ClockAlertVoiceCommand.DISMISS -> completeVoiceAlertCommand(alert, expectedJournal)
            ClockAlertVoiceCommand.SNOOZE -> {
                if (performSnooze(alert, snoozeDurationFor(alert), expectedJournal, ownedSessionId)) {
                    completeVoiceAlertCommand(alert, expectedJournal)
                }
            }
            ClockAlertVoiceCommand.ADD_ONE_MINUTE -> {
                if (performAddOneMinute(alert, expectedJournal, ownedSessionId)) {
                    completeVoiceAlertCommand(alert, expectedJournal)
                }
            }
        }
    }

    private fun completeVoiceAlertCommand(
        alert: TriggeredClockAlert,
        expectedJournal: WakeSessionJournal,
    ) {
        if (voiceJournal === expectedJournal) {
            voiceJournal = terminaliseClockAlertVoiceJournal(expectedJournal, completed = true)
            dismissAlert(alert)
        } else {
            // The command already reached the repository. Remove only its alert; preserve the
            // replacement voice journal, mic owner, and playback state.
            dismissAlert(alert, preserveVoiceSession = true)
        }
    }
    private suspend fun performAutoStop(alert: TriggeredClockAlert) {
        dismissAlert(alert)
    }

    private suspend fun performAutoSnooze(alert: TriggeredClockAlert, snoozeMs: Long = snoozeDurationMs) {
        val attemptJournal = voiceJournal
        val snoozeDuration = if (alert.type == ClockEventType.ALARM && snoozeMs > 0L) snoozeMs else ALARM_SNOOZE_MS
        runAutoSnoozeAction(
            snooze = {
                clockRepository.snoozeAlarm(
                    alarmId = alert.ownerId,
                    snoozedUntilMillis = System.currentTimeMillis() + snoozeDuration,
                    currentAutoSnoozeCount = alert.autoSnoozeCount,
                )
            },
            dismiss = { preserveVoiceSession ->
                dismissAlert(alert, preserveVoiceSession = preserveVoiceSession)
            },
            shouldPreserveVoiceSession = { voiceJournal !== attemptJournal },
        )
    }
    private suspend fun performSnooze(
        alert: TriggeredClockAlert,
        durationMs: Long,
        expectedJournal: WakeSessionJournal? = null,
        ownedSessionId: Long? = captureSessionId,
    ): Boolean {
        if (alert.type != ClockEventType.ALARM) {
            finishVoiceCapture(
                "Snooze is only available for alarms.",
                "unsupported_alert_command",
                expectedJournal,
                ownedSessionId,
            )
            return false
        }
        val success = clockRepository.snoozeAlarm(
            alarmId = alert.ownerId,
            snoozedUntilMillis = System.currentTimeMillis() + durationMs,
            currentAutoSnoozeCount = alert.autoSnoozeCount,
        )
        if (!success) {
            finishVoiceCapture("Couldn't snooze the alarm.", "command_execution_failed", expectedJournal, ownedSessionId)
        }
        return success
    }

    private suspend fun performAddOneMinute(
        alert: TriggeredClockAlert,
        expectedJournal: WakeSessionJournal? = null,
        ownedSessionId: Long? = captureSessionId,
    ): Boolean {
        val success = when (alert.type) {
            ClockEventType.ALARM -> clockRepository.snoozeAlarm(
                alarmId = alert.ownerId,
                snoozedUntilMillis = System.currentTimeMillis() + ALERT_ADD_MINUTE_MS,
                currentAutoSnoozeCount = alert.autoSnoozeCount,
            )
            ClockEventType.TIMER -> clockRepository.scheduleTimer(
                durationMs = ALERT_ADD_MINUTE_MS,
                label = alert.label.takeIf { it.isNotBlank() },
            ) is SchedulingResult.Success
            ClockEventType.PRE_ALARM -> false
        }
        if (!success) {
            finishVoiceCapture("Couldn't add one minute.", "command_execution_failed", expectedJournal, ownedSessionId)
        }
        return success
    }
    /**
     * Shows the notes for alert capture [sessionId] in the alert notification, ahead of the
     * listening prompt. The latest note is replayed, so one posted during the start is kept.
     */
    private fun watchVoiceNotices(sessionId: Long) {
        voiceNoticeJob?.cancel()
        voiceNoticeJob = serviceScope.launch {
            voiceInputController.noticesFor(sessionId).collect { note ->
                if (captureSessionId != sessionId || !isVoiceListening) return@collect
                voiceNotice = note
                currentAlert()?.let { voiceStatusMessage = listeningStatus(it) }
                refreshForeground()
            }
        }
    }

    private fun clearVoiceNotice() {
        voiceNoticeJob?.cancel()
        voiceNoticeJob = null
        voiceNotice = null
    }

    private fun listeningStatus(alert: TriggeredClockAlert): String =
        listOfNotNull(voiceNotice, alertVoiceListeningPrompt(alert.type)).joinToString(" ")

    private fun finishVoiceCapture(
        message: String,
        cancellationCategory: String,
        expectedJournal: WakeSessionJournal? = null,
        ownedSessionId: Long? = captureSessionId,
    ) {
        if (expectedJournal != null && voiceJournal !== expectedJournal) return
        val journalToFinish = expectedJournal ?: voiceJournal
        isVoiceListening = false
        clearVoiceNotice()
        captureSessionId = null
        handledVoiceTranscript = false
        voiceStatusMessage = message
        if (ownedSessionId != null) {
            voiceInputController.stopListening(ownedSessionId)
            voiceCaptureOwner.clear(ownedSessionId)
        }
        voiceJournal = terminaliseClockAlertVoiceJournal(journalToFinish, completed = false, cancellationCategory)
        if (activeAlerts.isEmpty()) {
            stopAlertSession()
        } else {
            refreshForeground()
            restorePlaybackAfterVoiceCapture()
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
        val soundUri = getStringExtra(ClockAlertContract.EXTRA_SOUND_URI)
        val autoSnoozeCount = getIntExtra(
            ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT,
            0,
        )
        return TriggeredClockAlert(
            ownerId = ownerId,
            label = label,
            title = title,
            type = type,
            occurrenceTriggerAtMillis = occurrenceTriggerAtMillis,
            soundUri = soundUri,
            autoSnoozeCount = autoSnoozeCount,
        )
    }
    companion object {
        @Volatile
        private var activeAlertSnapshot: Set<TriggeredClockAlert> = emptySet()

        internal fun hasActiveTimerAlerts(): Boolean =
            activeAlertSnapshot.any { it.type == ClockEventType.TIMER }

        internal fun createTriggerIntent(context: Context, alert: TriggeredClockAlert): Intent =
            Intent(context, ClockAlertService::class.java).apply {
                action = ClockAlertContract.ACTION_TRIGGER_ALERT
                putExtra(ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT, alert.autoSnoozeCount)
                putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
                putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
                putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
                putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
                putExtra(ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS, alert.occurrenceTriggerAtMillis ?: -1L)
                putExtra(ClockAlertContract.EXTRA_SOUND_URI, alert.soundUri)
            }

        internal fun trigger(context: Context, alert: TriggeredClockAlert) {
            ContextCompat.startForegroundService(
                context,
                createTriggerIntent(context, alert),
            )
        }
    }
}
