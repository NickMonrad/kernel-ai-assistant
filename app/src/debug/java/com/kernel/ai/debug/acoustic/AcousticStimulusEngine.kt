package com.kernel.ai.debug.acoustic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val ACOUSTIC_STIMULUS_LOG_TAG = "AcousticStimulus"
private const val RESULT_SCHEMA_VERSION = 1
private const val STREAM = AudioManager.STREAM_MUSIC

internal val STIMULUS_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

enum class OutputRoute {
    BUILT_IN_SPEAKER,
    EXTERNAL_BLUETOOTH,
    OTHER,
    UNKNOWN,
}

data class AudioSnapshot(
    val volumeBefore: Int,
    val maximumVolume: Int,
    val routeBefore: OutputRoute,
)

interface FocusHandle

sealed interface FocusRequestResult {
    data class Granted(val handle: FocusHandle) : FocusRequestResult
    data class Denied(val reason: String = "request_denied") : FocusRequestResult
}

internal interface StimulusAudioController {
    fun snapshot(): AudioSnapshot
    fun currentRoute(): OutputRoute
    fun setMediaVolume(volume: Int)
    fun currentMediaVolume(): Int
    fun requestFocus(): FocusRequestResult
    fun abandonFocus(handle: FocusHandle)
}

internal interface StimulusPlayer {
    fun setGain(gain: Float)
    fun setDataSource(fileDescriptor: FileDescriptor)
    fun setOnPreparedListener(listener: () -> Unit)
    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (what: Int, extra: Int) -> Unit)
    fun prepareAsync()
    fun start()
    fun release()
}

internal fun interface StimulusPlayerFactory {
    fun create(): StimulusPlayer
}

internal data class StimulusEvent(
    val name: String,
    val monotonicMs: Long,
    val wallClockMs: Long,
)

internal data class StimulusResult(
    val trialId: String?,
    val fixtureId: String?,
    val fixtureSha256: String?,
    val fixtureDurationMs: Long?,
    val requestWallClockMs: Long,
    val requestMonotonicMs: Long,
    val prepareMonotonicMs: Long?,
    val playbackStartMonotonicMs: Long?,
    val completionMonotonicMs: Long?,
    val cleanupMonotonicMs: Long?,
    val volumeBefore: Int?,
    val requestedVolume: Int?,
    val appliedVolume: Int?,
    val maximumVolume: Int?,
    val restoredVolume: Int?,
    val outputRouteBefore: OutputRoute?,
    val outputRouteDuring: OutputRoute?,
    val focusResult: String,
    val completionStatus: String,
    val errorCategory: String?,
    val timeout: Boolean,
    val overlapRejected: Boolean,
    val cleanupSuccess: Boolean,
    val exactRestorationVerified: Boolean,
    val events: List<StimulusEvent>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", RESULT_SCHEMA_VERSION)
        putOpt("trial_id", trialId)
        putOpt("fixture_id", fixtureId)
        putOpt("fixture_sha256", fixtureSha256)
        putOpt("fixture_duration_ms", fixtureDurationMs)
        put("request_wall_clock_ms", requestWallClockMs)
        put("request_monotonic_ms", requestMonotonicMs)
        putOpt("prepare_monotonic_ms", prepareMonotonicMs)
        putOpt("playback_start_monotonic_ms", playbackStartMonotonicMs)
        putOpt("completion_monotonic_ms", completionMonotonicMs)
        putOpt("cleanup_monotonic_ms", cleanupMonotonicMs)
        putOpt("volume_before", volumeBefore)
        putOpt("requested_volume", requestedVolume)
        putOpt("applied_volume", appliedVolume)
        putOpt("maximum_volume", maximumVolume)
        putOpt("restored_volume", restoredVolume)
        putOpt("output_route_before", outputRouteBefore?.name)
        putOpt("output_route_during", outputRouteDuring?.name)
        put("focus_result", focusResult)
        put("completion_status", completionStatus)
        putOpt("error_category", errorCategory)
        put("timeout", timeout)
        put("overlap_rejected", overlapRejected)
        put("cleanup_success", cleanupSuccess)
        put("exact_restoration_verified", exactRestorationVerified)
        put("events", JSONArray().apply {
            events.forEach { event ->
                put(
                    JSONObject().apply {
                        put("name", event.name)
                        put("monotonic_ms", event.monotonicMs)
                        put("wall_clock_ms", event.wallClockMs)
                    },
                )
            }
        })
    }
}

internal fun interface StimulusClock {
    fun wallClockMs(): Long
}

internal interface StimulusTimeSource {
    fun wallClockMs(): Long
    fun monotonicMs(): Long
}

internal object SystemStimulusTimeSource : StimulusTimeSource {
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
}

internal interface StimulusCancellation {
    fun cancel()
}

internal interface StimulusScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): StimulusCancellation
}

internal interface StimulusResultWriter {
    fun write(result: StimulusResult)
}

internal fun interface StimulusEventLogger {
    fun event(result: StimulusEvent, trialId: String?, fixtureId: String?)
}

internal object PlaybackGate {
    private val inProgress = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inProgress.compareAndSet(false, true)
    fun release() {
        inProgress.set(false)
    }
}

internal class AcousticStimulusEngine(
    private val fixtures: FixtureSource,
    private val audio: StimulusAudioController,
    private val playerFactory: StimulusPlayerFactory,
    private val scheduler: StimulusScheduler,
    private val time: StimulusTimeSource,
    private val resultWriter: StimulusResultWriter,
    private val eventLogger: StimulusEventLogger,
) {
    fun handle(
        parsed: InvocationParseResult,
        finish: (StimulusResult) -> Unit,
    ) {
        val requestWallClockMs = time.wallClockMs()
        val requestMonotonicMs = time.monotonicMs()
        when (parsed) {
            is InvocationParseResult.Invalid -> {
                val error = parsed.error
                val result = invalidResult(
                    trialId = error.trialId,
                    fixtureId = error.fixtureId,
                    requestWallClockMs = requestWallClockMs,
                    requestMonotonicMs = requestMonotonicMs,
                    errorCategory = error.category,
                    overlapRejected = false,
                )
                deliver(result, finish)
            }
            is InvocationParseResult.Valid -> start(
                parsed.invocation,
                requestWallClockMs,
                requestMonotonicMs,
                finish,
            )
        }
    }

    private fun deliver(result: StimulusResult, finish: (StimulusResult) -> Unit) {
        try {
            resultWriter.write(result)
        } catch (error: Exception) {
            Log.e(ACOUSTIC_STIMULUS_LOG_TAG, "result_write_failed", error)
        } finally {
            finish(result)
        }
    }

    private fun start(
        invocation: StimulusInvocation,
        requestWallClockMs: Long,
        requestMonotonicMs: Long,
        finish: (StimulusResult) -> Unit,
    ) {
        if (!PlaybackGate.tryAcquire()) {
            val result = invalidResult(
                trialId = invocation.trialId,
                fixtureId = invocation.fixtureId,
                requestWallClockMs = requestWallClockMs,
                requestMonotonicMs = requestMonotonicMs,
                errorCategory = "overlap_rejected",
                overlapRejected = true,
            )
            deliver(result, finish)
            return
        }
        val state = SessionState(invocation, requestWallClockMs, requestMonotonicMs, finish)
        try {
            state.timeout = scheduler.schedule(AcousticStimulusContract.HARD_TIMEOUT_MS) {
                state.complete("timeout", "playback_timeout", timedOut = true)
            }
            val resolved = fixtures.resolveAndValidate(invocation.fixtureId)
            state.fixture = resolved
            if (state.isComplete()) return
            state.snapshot = audio.snapshot()
            if (state.snapshot!!.routeBefore != OutputRoute.BUILT_IN_SPEAKER) {
                state.complete("rejected", "invalid_output_route")
                return
            }
            if (invocation.volumeIndex !in 1..state.snapshot!!.maximumVolume) {
                state.complete("rejected", "unsafe_volume_index")
                return
            }
            state.requestedVolume = invocation.volumeIndex
            audio.setMediaVolume(invocation.volumeIndex)
            state.appliedVolume = audio.currentMediaVolume()
            if (state.appliedVolume != invocation.volumeIndex) {
                state.complete("rejected", "volume_apply_failed")
                return
            }
            when (val focus = audio.requestFocus()) {
                is FocusRequestResult.Denied -> {
                    state.focusResult = "denied:${focus.reason}"
                    state.complete("rejected", "audio_focus_denied")
                    return
                }
                is FocusRequestResult.Granted -> {
                    state.focusHandle = focus.handle
                    state.focusResult = "granted"
                }
            }
            if (audio.currentRoute() != OutputRoute.BUILT_IN_SPEAKER) {
                state.complete("rejected", "invalid_output_route")
                return
            }
            state.source = fixtures.openFixture(resolved)
            state.player = playerFactory.create()
            state.player!!.setGain(invocation.playerGain)
            state.player!!.setOnPreparedListener {
                if (state.isComplete()) return@setOnPreparedListener
                state.record("prepared")
                state.prepareMonotonicMs = time.monotonicMs()
                val routeValid = try {
                    audio.currentRoute() == OutputRoute.BUILT_IN_SPEAKER
                } catch (_: Exception) {
                    false
                }
                if (!routeValid) {
                    state.complete("rejected", "invalid_output_route")
                    return@setOnPreparedListener
                }
                try {
                    state.player!!.start()
                    state.record("started")
                    state.playbackStartMonotonicMs = time.monotonicMs()
                } catch (_: Exception) {
                    state.complete("failed", "playback_start_failed")
                }
            }
            state.player!!.setOnCompletionListener {
                state.complete("completed", null)
            }
            state.player!!.setOnErrorListener { _, _ ->
                state.complete("failed", "playback_error")
            }
            state.player!!.setDataSource(state.source!!.fd)
            state.player!!.prepareAsync()
        } catch (error: FixtureValidationException) {
            state.complete("rejected", error.category)
        } catch (_: SecurityException) {
            state.complete("failed", "audio_state_access_failed")
        } catch (_: Exception) {
            state.complete("failed", "prepare_failed")
        }
    }

    private fun invalidResult(
        trialId: String?,
        fixtureId: String?,
        requestWallClockMs: Long,
        requestMonotonicMs: Long,
        errorCategory: String,
        overlapRejected: Boolean,
    ): StimulusResult = StimulusResult(
        trialId = trialId,
        fixtureId = fixtureId,
        fixtureSha256 = null,
        fixtureDurationMs = null,
        requestWallClockMs = requestWallClockMs,
        requestMonotonicMs = requestMonotonicMs,
        prepareMonotonicMs = null,
        playbackStartMonotonicMs = null,
        completionMonotonicMs = requestMonotonicMs,
        cleanupMonotonicMs = requestMonotonicMs,
        volumeBefore = null,
        requestedVolume = null,
        appliedVolume = null,
        maximumVolume = null,
        restoredVolume = null,
        outputRouteBefore = null,
        outputRouteDuring = null,
        focusResult = "not_requested",
        completionStatus = "rejected",
        errorCategory = errorCategory,
        timeout = false,
        overlapRejected = overlapRejected,
        cleanupSuccess = true,
        exactRestorationVerified = true,
        events = emptyList(),
    )

    private inner class SessionState(
        private val invocation: StimulusInvocation,
        private val requestWallClockMs: Long,
        private val requestMonotonicMs: Long,
        private val finish: (StimulusResult) -> Unit,
    ) {
        var fixture: ResolvedFixture? = null
        var snapshot: AudioSnapshot? = null
        var requestedVolume: Int? = null
        var appliedVolume: Int? = null
        var restoredVolume: Int? = null
        var focusHandle: FocusHandle? = null
        var focusResult: String = "not_requested"
        var source: FileInputStream? = null
        var player: StimulusPlayer? = null
        var timeout: StimulusCancellation? = null
        var prepareMonotonicMs: Long? = null
        var playbackStartMonotonicMs: Long? = null
        var outputRouteDuring: OutputRoute? = null
        private val events = mutableListOf<StimulusEvent>()
        @Volatile
        private var completed = false

        fun isComplete(): Boolean = completed

        fun record(name: String) {
            val event = StimulusEvent(name, time.monotonicMs(), time.wallClockMs())
            events += event
            try {
                eventLogger.event(event, invocation.trialId, invocation.fixtureId)
            } catch (error: Exception) {
                Log.e(ACOUSTIC_STIMULUS_LOG_TAG, "event_log_failed", error)
            }
        }

        @Synchronized
        fun complete(status: String, errorCategory: String?, timedOut: Boolean = false) {
            if (completed) return
            completed = true
            timeout?.cancel()
            val completionMonotonicMs = time.monotonicMs()
            var cleanupSuccess = true
            var exactRestorationVerified = true
            var cleanupError: String? = null
            record(if (timedOut) "timeout" else if (errorCategory == null) "completed" else "error")
            val capturedSnapshot = snapshot
            if (capturedSnapshot != null) {
                outputRouteDuring = try {
                    audio.currentRoute()
                } catch (_: Exception) {
                    cleanupSuccess = false
                    cleanupError = "output_route_unavailable"
                    OutputRoute.UNKNOWN
                }
            }
            try {
                player?.release()
            } catch (_: Exception) {
                cleanupSuccess = false
                cleanupError = "player_release_failed"
            }
            try {
                source?.close()
            } catch (_: Exception) {
                cleanupSuccess = false
                cleanupError = cleanupError ?: "fixture_fd_close_failed"
            }
            focusHandle?.let { handle ->
                try {
                    audio.abandonFocus(handle)
                } catch (_: Exception) {
                    cleanupSuccess = false
                    cleanupError = cleanupError ?: "audio_focus_abandon_failed"
                }
            }
            if (capturedSnapshot != null) {
                try {
                    audio.setMediaVolume(capturedSnapshot.volumeBefore)
                    restoredVolume = audio.currentMediaVolume()
                    exactRestorationVerified = restoredVolume == capturedSnapshot.volumeBefore
                    if (!exactRestorationVerified) {
                        cleanupSuccess = false
                        cleanupError = cleanupError ?: "volume_restoration_failed"
                    }
                } catch (_: Exception) {
                    cleanupSuccess = false
                    exactRestorationVerified = false
                    cleanupError = cleanupError ?: "volume_restoration_failed"
                }
            }
            val result = StimulusResult(
                trialId = invocation.trialId,
                fixtureId = invocation.fixtureId,
                fixtureSha256 = fixture?.metadata?.sha256,
                fixtureDurationMs = fixture?.metadata?.durationMs,
                requestWallClockMs = requestWallClockMs,
                requestMonotonicMs = requestMonotonicMs,
                prepareMonotonicMs = prepareMonotonicMs,
                playbackStartMonotonicMs = playbackStartMonotonicMs,
                completionMonotonicMs = completionMonotonicMs,
                cleanupMonotonicMs = time.monotonicMs(),
                volumeBefore = capturedSnapshot?.volumeBefore,
                requestedVolume = requestedVolume,
                appliedVolume = appliedVolume,
                maximumVolume = capturedSnapshot?.maximumVolume,
                restoredVolume = restoredVolume,
                outputRouteBefore = capturedSnapshot?.routeBefore,
                outputRouteDuring = outputRouteDuring,
                focusResult = focusResult,
                completionStatus = if (cleanupError == null) status else "invalid",
                errorCategory = cleanupError ?: errorCategory,
                timeout = timedOut,
                overlapRejected = false,
                cleanupSuccess = cleanupSuccess,
                exactRestorationVerified = exactRestorationVerified,
                events = events.toList(),
            )
            PlaybackGate.release()
            deliver(result, finish)
        }
    }
}

internal class AndroidMediaPlayerFactory : StimulusPlayerFactory {
    override fun create(): StimulusPlayer = AndroidStimulusPlayer(MediaPlayer().apply {
        setAudioAttributes(STIMULUS_AUDIO_ATTRIBUTES)
    })
}

private class AndroidStimulusPlayer(private val player: MediaPlayer) : StimulusPlayer {
    override fun setGain(gain: Float) = player.setVolume(gain, gain)
    override fun setDataSource(fileDescriptor: FileDescriptor) = player.setDataSource(fileDescriptor)
    override fun setOnPreparedListener(listener: () -> Unit) {
        player.setOnPreparedListener { listener() }
    }
    override fun setOnCompletionListener(listener: () -> Unit) {
        player.setOnCompletionListener { listener() }
    }
    override fun setOnErrorListener(listener: (what: Int, extra: Int) -> Unit) {
        player.setOnErrorListener { _, what, extra ->
            listener(what, extra)
            true
        }
    }
    override fun prepareAsync() = player.prepareAsync()
    override fun start() = player.start()
    override fun release() = player.release()
}

internal class AndroidStimulusAudioController(
    context: Context,
) : StimulusAudioController {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun snapshot(): AudioSnapshot = AudioSnapshot(
        volumeBefore = audioManager.getStreamVolume(STREAM),
        maximumVolume = audioManager.getStreamMaxVolume(STREAM),
        routeBefore = currentRoute(),
    )

    override fun currentRoute(): OutputRoute {
        val devices = try {
            audioManager.getAudioDevicesForAttributes(STIMULUS_AUDIO_ATTRIBUTES)
        } catch (_: SecurityException) {
            return OutputRoute.UNKNOWN
        }
        if (devices.isEmpty()) return OutputRoute.UNKNOWN
        if (devices.any { it.type in BLUETOOTH_OUTPUT_TYPES }) return OutputRoute.EXTERNAL_BLUETOOTH
        return if (devices.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
            OutputRoute.BUILT_IN_SPEAKER
        } else {
            OutputRoute.OTHER
        }
    }

    override fun setMediaVolume(volume: Int) = audioManager.setStreamVolume(STREAM, volume, 0)
    override fun currentMediaVolume(): Int = audioManager.getStreamVolume(STREAM)

    override fun requestFocus(): FocusRequestResult {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(STIMULUS_AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener { }
            .setWillPauseWhenDucked(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            FocusRequestResult.Granted(AndroidFocusHandle(request))
        } else {
            FocusRequestResult.Denied(result.toString())
        }
    }

    override fun abandonFocus(handle: FocusHandle) {
        audioManager.abandonAudioFocusRequest((handle as AndroidFocusHandle).request)
    }

    private data class AndroidFocusHandle(val request: AudioFocusRequest) : FocusHandle

    companion object {
        private val BLUETOOTH_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}

internal class HandlerStimulusScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : StimulusScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): StimulusCancellation {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return object : StimulusCancellation {
            override fun cancel() {
                handler.removeCallbacks(runnable)
            }
        }
    }
}

internal class AndroidStimulusResultWriter(
    private val context: Context,
) : StimulusResultWriter {
    override fun write(result: StimulusResult) {
        val directory = AcousticFixtureStorage.resultDirectory(context).apply { mkdirs() }
        val safeId = result.trialId?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) }
            ?: "invalid-${result.requestMonotonicMs}"
        val destination = File(directory, "$safeId.json")
        val temporary = File(directory, "$safeId.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(result.toJson().toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(destination)) { "result_write_failed" }
    }
}

internal object AndroidStimulusEventLogger : StimulusEventLogger {
    override fun event(result: StimulusEvent, trialId: String?, fixtureId: String?) {
        val json = JSONObject().apply {
            put("event", result.name)
            put("trial_id", trialId)
            put("fixture_id", fixtureId)
            put("monotonic_ms", result.monotonicMs)
            put("wall_clock_ms", result.wallClockMs)
        }
        Log.i(ACOUSTIC_STIMULUS_LOG_TAG, json.toString())
    }
}
