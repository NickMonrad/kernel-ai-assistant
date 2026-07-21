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
internal sealed interface CaptureStartResult {
    val captureSessionId: Long

    data class Started(
        override val captureSessionId: Long,
        val ownedStartEvents: List<VoiceInputEvent>,
    ) : CaptureStartResult

    data class Unavailable(
        val message: String?,
    ) : CaptureStartResult {
        override val captureSessionId: Long get() = -1L
    }
}

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
 * Bounded STT session startup with a temporary buffered collector.
 *
 * Subscribes to [VoiceInputController.events] **before** calling [startListening] so that
 * synchronously emitted events are captured. Events belonging to the returned session are
 * drained and returned in [CaptureStartResult.Started.ownedStartEvents].
 *
 * The collector always terminates before this function returns.
 */
internal suspend fun bufferedCaptureSession(
    voiceInputController: VoiceInputController,
    mode: VoiceCaptureMode,
): CaptureStartResult = coroutineScope {
    val startupChannel = Channel<VoiceInputEvent>(Channel.BUFFERED)
    val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
        voiceInputController.events.collect { startupChannel.send(it) }
    }
    try {
        when (val startResult = voiceInputController.startListening(mode)) {
            is VoiceInputStartResult.Started -> {
                val ownedEvents = mutableListOf<VoiceInputEvent>()
                while (true) {
                    val buffered = startupChannel.tryReceive().getOrNull() ?: break
                    if (buffered.captureSessionId == startResult.captureSessionId) {
                        ownedEvents.add(buffered)
                    }
                }
                CaptureStartResult.Started(
                    captureSessionId = startResult.captureSessionId,
                    ownedStartEvents = ownedEvents,
                )
            }
            is VoiceInputStartResult.Unavailable -> CaptureStartResult.Unavailable(startResult.message)
        }
    } finally {
        collectorJob.cancelAndJoin()
        startupChannel.cancel()
    }
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

@AndroidEntryPoint
class ClockAlertService : Service() {
    @Inject lateinit var startListeningCuePlayer: StartListeningCuePlayer
    @Inject lateinit var clockRepository: ClockRepository
    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var voiceInputPreferences: VoiceInputPreferences

    private var captureSessionId: Long? = null
    private var voiceJournal: WakeSessionJournal? = null
    private val activeAlerts = linkedSetOf<TriggeredClockAlert>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voiceEventsJob: Job? = null
    private var voicePreferencesJob: Job? = null

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val vibratorManager: VibratorManager
        get() = getSystemService(VibratorManager::class.java)
    private var clockSoundConfigJob: Job? = null
    private var clockAlertConfigJob: Job? = null
    private var autoStartVoiceJob: Job? = null
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
            voiceInputController.events.collectLatest(::handleVoiceEvent)
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
                val dismissed = dismissAlertsMatching { it.type == ClockEventType.TIMER }
                if (dismissed == 0 && activeAlerts.isEmpty()) stopSelf()
            }

            ClockAlertContract.ACTION_SNOOZE_ALERT -> {
                val alert = intent.toTriggeredClockAlert()?.let { findActiveAlert(it.ownerId) } ?: currentAlert()
                if (alert != null) {
                    serviceScope.launch { performSnooze(alert, snoozeDurationFor(alert)) }
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
            voiceInputController.stopListening()
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
        captureSessionId = null
        voiceInputController.stopListening()
        voiceEventsJob?.cancel()
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
        voiceInputController.stopListening()
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dismissAlert(alert: TriggeredClockAlert) {
        dismissAlertsMatching { it.ownerId == alert.ownerId }
    }

    private fun dismissAlertsMatching(predicate: (TriggeredClockAlert) -> Boolean): Int {
        val toDismiss = activeAlerts.filter(predicate)
        if (toDismiss.isEmpty()) return 0

        toDismiss.forEach {
            cancelLifecycleTimeout(it.ownerId)
            activeAlertConfigs.remove(it.ownerId)
        }
        activeAlerts.removeAll(predicate)
        syncActiveAlertSnapshot()
        cancelAutoStartVoiceControl()
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
        isVoiceListening = true
        handledVoiceTranscript = false
        captureSessionId = null
        voiceJournal = null
        voiceStatusMessage = if (autoStarted) "Listening for alert commands…" else alertVoiceListeningPrompt(alert.type)
        duckPlaybackForVoiceControl()
        refreshForeground()
        serviceScope.launch {
            try {
                when (val result = bufferedCaptureSession(voiceInputController, VoiceCaptureMode.AlertCommand)) {
                    is CaptureStartResult.Started -> {
                        captureSessionId = result.captureSessionId
                        val journal = WakeSessionJournal(
                            generationId = 0L,
                            sessionId = result.captureSessionId,
                        )
                        journal.start()
                        voiceJournal = journal
                        for (event in result.ownedStartEvents) {
                            handleVoiceEvent(event)
                        }
                    }
                    is CaptureStartResult.Unavailable -> {
                        captureSessionId = null
                        finishVoiceCapture(alertVoiceUnavailableMessage(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ClockAlertService: voice startup failed", e)
                captureSessionId = null
                finishVoiceCapture("Voice command startup failed.")
            }
        }
    }
    private suspend fun handleVoiceEvent(event: VoiceInputEvent) {
        if (!isOwnedAlertEvent(event, captureSessionId, isVoiceListening)) return
        when (event) {
            is VoiceInputEvent.ListeningStarted -> {
                if (shouldPlayClockAlertListeningCue(event, captureSessionId, isVoiceListening)) {
                    voiceJournal?.let { journal ->
                        journal.record(AcousticEventType.STT_READY)
                        recordClockAlertCue(journal, startListeningCuePlayer)
                    }
                    currentAlert()?.let { voiceStatusMessage = alertVoiceListeningPrompt(it.type) }
                    refreshForeground()
                }
            }
            is VoiceInputEvent.SpeechDetected -> Unit
            is VoiceInputEvent.PartialTranscript -> Unit
            is VoiceInputEvent.Transcript -> {
                captureSessionId = null
                handledVoiceTranscript = true
                val alert = currentAlert() ?: return finishVoiceCapture("No active alert to control.")
                handleVoiceTranscript(alert, event.text)
            }
            is VoiceInputEvent.Error -> {
                captureSessionId = null
                handledVoiceTranscript = true
                finishVoiceCapture(event.message)
            }
            is VoiceInputEvent.ListeningStopped -> {
                if (!handledVoiceTranscript) {
                    captureSessionId = null
                    finishVoiceCapture("I didn't catch a supported alert command.")
                }
            }
        }
    }

    private suspend fun performAutoStop(alert: TriggeredClockAlert) {
        dismissAlert(alert)
    }

    private suspend fun performAutoSnooze(alert: TriggeredClockAlert, snoozeMs: Long = snoozeDurationMs) {
        val snoozeDuration = if (alert.type == ClockEventType.ALARM && snoozeMs > 0L) snoozeMs else ALARM_SNOOZE_MS
        val success = clockRepository.snoozeAlarm(
            alarmId = alert.ownerId,
            snoozedUntilMillis = System.currentTimeMillis() + snoozeDuration,
            currentAutoSnoozeCount = alert.autoSnoozeCount,
        )
        if (success) {
            dismissAlert(alert)
        } else {
            performAutoStop(alert)
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
            ClockAlertVoiceCommand.DISMISS -> {
                dismissAlert(alert)
            }
            ClockAlertVoiceCommand.SNOOZE -> performSnooze(alert, snoozeDurationFor(alert))
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
            currentAutoSnoozeCount = alert.autoSnoozeCount,
        )
        if (success) {
            dismissAlert(alert)
        } else {
            finishVoiceCapture("Couldn't snooze the alarm.")
        }
    }
    private suspend fun performAddOneMinute(alert: TriggeredClockAlert) {
        val success = when (alert.type) {
            ClockEventType.ALARM -> {
                clockRepository.snoozeAlarm(
                    alarmId = alert.ownerId,
                    snoozedUntilMillis = System.currentTimeMillis() + ALERT_ADD_MINUTE_MS,
                    currentAutoSnoozeCount = alert.autoSnoozeCount,
                )
            }

            ClockEventType.TIMER -> clockRepository.scheduleTimer(
                durationMs = ALERT_ADD_MINUTE_MS,
                label = alert.label.takeIf { it.isNotBlank() },
            ) is SchedulingResult.Success

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
        captureSessionId = null
        handledVoiceTranscript = false
        voiceStatusMessage = message
        voiceInputController.stopListening()
        // Close any active voice journal with exactly one terminal event
        voiceJournal?.let { journal ->
            // Transcript means success; otherwise the capture was cancelled/incomplete
            journal.complete()
            voiceJournal = null
        }
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
            type = type,
            title = title,
            label = label,
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
