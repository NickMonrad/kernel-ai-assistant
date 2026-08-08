package com.kernel.ai.debug.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Debug-only target-side PCM capture for acoustic evidence (#1410).
 *
 * Records the exact detector-equivalent microphone input (16 kHz mono int16,
 * `VOICE_RECOGNITION` — the same configuration as the production
 * `OnnxWakeWordDetector`) into app-private storage while a runner trial is in
 * flight, so the preserved private run can correlate raw target audio with
 * the trial's journal, capture-energy and classifier evidence.  The capture
 * is a passive second `AudioRecord` client: it never touches detector,
 * gate, Stage-1/2/3, threshold, verifier or activation behaviour.
 *
 * Invocation (explicit component only, never implicit):
 *
 * ```
 * adb shell am broadcast -n com.kernel.ai.debug/com.kernel.ai.debug.capture.TargetCaptureReceiver \
 *   -a com.kernel.ai.debug.action.CAPTURE_START --es trial_id <trial> [--es fixture_id <fixture>]
 * adb shell am broadcast -n com.kernel.ai.debug/com.kernel.ai.debug.capture.TargetCaptureReceiver \
 *   -a com.kernel.ai.debug.action.CAPTURE_STOP --es trial_id <trial>
 * ```
 *
 * START returns [TargetCaptureContract.RESULT_OK] when recording begins, [TargetCaptureContract.RESULT_ERROR] on an
 * invalid invocation or recorder failure.  STOP writes
 * `files/acoustic-capture/<trial_id>.wav` plus a metadata sidecar
 * `<trial_id>.json`, and returns [TargetCaptureContract.RESULT_OK] with a
 * simple `stopped:<trial_id>` token (the full metadata is read from the
 * sidecar), or [TargetCaptureContract.RESULT_NOT_RECORDING] when no capture
 * is active.  Artifacts stay in app-private storage until the runner pulls
 * them into the private run directory; they are never published.
 *
 * Recording is hard-capped ([MAX_RECORDING_MS]) so a lost STOP can never
 * grow storage without bound; an overflowed capture is still written with
 * `overflowed=true` so evidence never silently truncates.
 */
class TargetCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (!isExplicitReceiverInvocation(appContext, intent)) {
                        finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "explicit_component_required")
                        return@post
                    }
                    when (intent.action) {
                        TargetCaptureContract.ACTION_START -> {
                            val trialId = intent.getStringExtra(TargetCaptureContract.EXTRA_TRIAL_ID)
                            val fixtureId = intent.getStringExtra(TargetCaptureContract.EXTRA_FIXTURE_ID)
                            if (trialId.isNullOrBlank() || !VALID_TRIAL_ID.matches(trialId)) {
                                finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "invalid_trial_id")
                                return@post
                            }
                            val error = startCapture(appContext, trialId, fixtureId)
                            if (error != null) {
                                finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, error)
                            } else {
                                finishWith(pendingResult, TargetCaptureContract.RESULT_OK, "started:$trialId")
                            }
                        }
                        TargetCaptureContract.ACTION_STOP -> {
                            val trialId = intent.getStringExtra(TargetCaptureContract.EXTRA_TRIAL_ID)
                            if (trialId.isNullOrBlank() || !VALID_TRIAL_ID.matches(trialId)) {
                                finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "invalid_trial_id")
                                return@post
                            }
                            val summary = stopCapture(appContext, trialId)
                            val error = summary?.first
                            when {
                                summary == null ->
                                    finishWith(pendingResult, TargetCaptureContract.RESULT_NOT_RECORDING, "not_recording:$trialId")
                                error != null ->
                                    finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, error)
                                else ->
                                    // The full metadata lives in the app-private
                                    // sidecar (<trial_id>.json); the broadcast
                                    // result stays simple so the runner's
                                    // ordered-broadcast parser can carry it.
                                    finishWith(pendingResult, TargetCaptureContract.RESULT_OK, "stopped:$trialId")
                            }
                        }
                        else -> finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "unsupported_action")
                    }
                } catch (error: Exception) {
                    Log.e(LOG_TAG, "receiver_failed", error)
                    finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "receiver_failed")
                }
            }
        } catch (error: Exception) {
            Log.e(LOG_TAG, "receiver_dispatch_failed", error)
            finishWith(pendingResult, TargetCaptureContract.RESULT_ERROR, "receiver_dispatch_failed")
        }
    }

    private fun startCapture(context: Context, trialId: String, fixtureId: String?): String? {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return "record_audio_permission_missing"
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            TARGET_CAPTURE_SAMPLE_RATE,
            TARGET_CAPTURE_CHANNEL_CONFIG,
            TARGET_CAPTURE_AUDIO_FORMAT,
        )
        if (minBuffer <= 0) return "recorder_buffer_unavailable"
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                TARGET_CAPTURE_SAMPLE_RATE,
                TARGET_CAPTURE_CHANNEL_CONFIG,
                TARGET_CAPTURE_AUDIO_FORMAT,
                minBuffer.coerceAtLeast(FRAME_SAMPLES * Short.SIZE_BYTES * 2),
            )
        } catch (error: Exception) {
            Log.w(LOG_TAG, "AudioRecord construction failed", error)
            return "recorder_construction_failed"
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "recorder_initialisation_failed"
        }
        synchronized(CAPTURE_LOCK) {
            // A stale active capture (lost STOP) is cancelled so the next
            // trial can still record; its data is dropped, never published.
            active?.cancel()
            active = ActiveCapture(
                trialId = trialId,
                fixtureId = fixtureId,
                startedWallClockMs = System.currentTimeMillis(),
                startedMonotonicMs = SystemClock.elapsedRealtime(),
            )
        }
        val started = synchronized(CAPTURE_LOCK) { active }
        if (started == null) return "recorder_start_failed"
        val thread = Thread({
            try {
                recorder.startRecording()
            } catch (error: Exception) {
                Log.w(LOG_TAG, "startRecording failed", error)
                synchronized(CAPTURE_LOCK) {
                    if (active === started) active = null
                }
                recorder.release()
                return@Thread
            }
            val frame = ShortArray(FRAME_SAMPLES)
            try {
                while (true) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    if (started.isCancelled()) break
                    val bytes = ByteArray(read * Short.SIZE_BYTES)
                    for (index in 0 until read) {
                        val value = frame[index].toInt()
                        bytes[index * 2] = (value and 0xFF).toByte()
                        bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    val overflowed = started.append(bytes)
                    if (overflowed) break
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }, "target-capture-${trialId.take(24)}")
        started.attach(thread)
        thread.start()
        return null
    }

    /** Stops the active capture and persists WAV + metadata. Returns null on success. */
    private fun stopCapture(
        context: Context,
        trialId: String,
    ): Pair<String?, JSONObject>? {
        val stopped = synchronized(CAPTURE_LOCK) {
            val current = active
            if (current == null || current.trialId != trialId) {
                null
            } else {
                active = null
                current.cancel()
                current
            }
        }
        if (stopped == null) return null
        val thread = stopped.thread
        if (thread != null) {
            try {
                thread.join(CAPTURE_JOIN_TIMEOUT_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val (pcmBytes, overflowed) = stopped.snapshot()
        val stoppedWallClockMs = System.currentTimeMillis()
        val stoppedMonotonicMs = SystemClock.elapsedRealtime()
        if (pcmBytes.isEmpty()) {
            return Pair("empty_capture", JSONObject())
        }
        val dir = captureDirectory(context)
        if (dir == null) return Pair("capture_directory_unavailable", JSONObject())
        try {
            writeWav(File(dir, "$trialId.wav"), pcmBytes)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "wav write failed", error)
            return Pair("wav_write_failed", JSONObject())
        }
        val durationMs = (pcmBytes.size / (TARGET_CAPTURE_SAMPLE_RATE * Short.SIZE_BYTES).toLong()) * 1000L
        val summary = JSONObject().apply {
            put("trial_id", trialId)
            put("bytes", pcmBytes.size)
            put("duration_ms", durationMs)
            put("started_wall_clock_ms", stopped.startedWallClockMs)
            put("started_monotonic_ms", stopped.startedMonotonicMs)
            put("stopped_wall_clock_ms", stoppedWallClockMs)
            put("stopped_monotonic_ms", stoppedMonotonicMs)
            put("sample_rate", TARGET_CAPTURE_SAMPLE_RATE)
            put("channels", 1)
            put("overflowed", overflowed)
        }
        try {
            File(dir, "$trialId.json").writeText(summary.toString())
        } catch (error: Exception) {
            Log.e(LOG_TAG, "metadata write failed", error)
            return Pair("metadata_write_failed", summary)
        }
        Log.i(LOG_TAG, "capture complete: $trialId bytes=${pcmBytes.size} overflowed=$overflowed")
        return Pair(null, summary)
    }

    private fun captureDirectory(context: Context): File? {
        return try {
            val dir = File(context.filesDir, CAPTURE_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                null
            } else {
                dir
            }
        } catch (error: Exception) {
            Log.e(LOG_TAG, "capture directory unavailable", error)
            null
        }
    }

    private fun isExplicitReceiverInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == TargetCaptureReceiver::class.java.name

    private fun finishWith(pendingResult: PendingResult, code: Int, data: String) {
        pendingResult.setResultCode(code)
        pendingResult.setResultData(data)
        pendingResult.finish()
    }

    private class ActiveCapture(
        val trialId: String,
        val fixtureId: String?,
        val startedWallClockMs: Long,
        val startedMonotonicMs: Long,
    ) {
        private val buffer = ByteArrayOutputStream()
        private val lock = Any()
        @Volatile private var cancelled = false
        @Volatile private var overflowed = false
        @Volatile var thread: Thread? = null
            private set

        fun attach(captureThread: Thread) {
            thread = captureThread
        }

        fun cancel() {
            cancelled = true
        }

        fun isCancelled(): Boolean = cancelled

        /** Appends one frame; returns true when the hard cap was exceeded. */
        fun append(bytes: ByteArray): Boolean {
            val exceed = synchronized(lock) {
                if (buffer.size() + bytes.size > MAX_PCM_BYTES) {
                    overflowed = true
                    true
                } else {
                    buffer.write(bytes)
                    false
                }
            }
            if (exceed) cancelled = true
            return exceed
        }

        fun snapshot(): Pair<ByteArray, Boolean> = synchronized(lock) {
            Pair(buffer.toByteArray(), overflowed)
        }
    }

    private companion object {
        val VALID_TRIAL_ID = Regex("[A-Za-z0-9._-]{1,120}")
        const val FRAME_SAMPLES = 1_280
        const val MAX_RECORDING_MS = 20L * 60L * 1000L
        const val MAX_PCM_BYTES = MAX_RECORDING_MS / 1000L * TARGET_CAPTURE_SAMPLE_RATE * Short.SIZE_BYTES
        const val CAPTURE_JOIN_TIMEOUT_MS = 15_000L
        const val CAPTURE_DIR = "acoustic-capture"
        const val LOG_TAG = "TargetCapture"
        val CAPTURE_LOCK = Any()
        @Volatile var active: ActiveCapture? = null
    }
}

/** Explicit broadcast contract for the debug target capture receiver. */
object TargetCaptureContract {
    const val ACTION_START = "com.kernel.ai.debug.action.CAPTURE_START"
    const val ACTION_STOP = "com.kernel.ai.debug.action.CAPTURE_STOP"
    const val EXTRA_TRIAL_ID = "trial_id"
    const val EXTRA_FIXTURE_ID = "fixture_id"

    /** Recording started / stopped and artifact written. */
    const val RESULT_OK = 0

    /** No active capture matched the STOP request. */
    const val RESULT_NOT_RECORDING = 1

    /** Invalid invocation or recorder/storage failure. */
    const val RESULT_ERROR = 2
}

/** Detector-equivalent capture format (16 kHz mono int16, VOICE_RECOGNITION). */
const val TARGET_CAPTURE_SAMPLE_RATE = 16_000
private const val TARGET_CAPTURE_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val TARGET_CAPTURE_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

/** Writes a minimal 16-bit PCM mono WAV (16 kHz) with the given payload. */
private fun writeWav(file: File, pcm: ByteArray) {
    val dataSize = pcm.size
    file.outputStream().buffered().use { out ->
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeUInt32(out, 36L + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeUInt32(out, 16L)
        writeLeUInt16(out, 1) // PCM
        writeLeUInt16(out, 1) // mono
        writeLeUInt32(out, TARGET_CAPTURE_SAMPLE_RATE.toLong())
        writeLeUInt32(out, (TARGET_CAPTURE_SAMPLE_RATE * 2).toLong())
        writeLeUInt16(out, 2)
        writeLeUInt16(out, 16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeLeUInt32(out, dataSize.toLong())
        out.write(pcm)
    }
}

private fun writeLeUInt16(out: java.io.OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
}

private fun writeLeUInt32(out: java.io.OutputStream, value: Long) {
    out.write((value and 0xFF).toInt())
    out.write(((value shr 8) and 0xFF).toInt())
    out.write(((value shr 16) and 0xFF).toInt())
    out.write(((value shr 24) and 0xFF).toInt())
}
