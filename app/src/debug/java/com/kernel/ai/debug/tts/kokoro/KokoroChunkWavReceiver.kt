package com.kernel.ai.debug.tts.kokoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoicePcmTrack
import com.kernel.ai.core.voice.VoiceReplyHooks
import com.kernel.ai.core.voice.VoiceReplyStream
import com.kernel.ai.debug.sample.SampleWav
import com.kernel.ai.debug.tts.ChunkWavRecorder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (#2114): renders reply texts through the shared [VoiceReplyStream] with the Kokoro ExecuTorch lane
 * as the synthesis engine. Each text is one reply, rendered in order, with [ChunkWavRecorder] standing in for
 * AudioTrack. The JSON records first-chunk latency per reply and, when `stop_after_ms` is given, the latency from
 * the stop request until the reply's synthesis has returned (a native run in progress finishes first).
 *
 * Extras: `paths` = `|`-separated absolute, app-readable text files; `label` = output name; optional
 * `stop_after_ms` = aborts each reply that long after it starts. Output: `files/kokoro_chunk_wav/<label>.wav` and
 * `<label>.json`; the result data is the JSON path. Explicit component only; the manifest entry requires DUMP.
 *
 * Runs without a foreground service, so the app must stay in the foreground during a render. A render started
 * while the app was in the background stalled at 0% CPU in the #2114 device run (cause not confirmed).
 */
class KokoroChunkWavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "kokoro chunk wav: a render is already active")
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val json = render(appContext, intent)
                Log.i(TAG, "kokoro chunk wav done path=${json.absolutePath}")
            } catch (error: Throwable) {
                Log.e(TAG, "kokoro chunk wav failed", error)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun render(context: Context, intent: Intent): File {
        val label = (intent.getStringExtra(EXTRA_LABEL) ?: error("label extra required"))
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        val paths = intent.getStringExtra(EXTRA_PATHS)?.split('|')?.filter(String::isNotBlank)
            ?: error("paths extra required")
        val stopAfterMs = intent.getStringExtra(EXTRA_STOP_AFTER_MS)?.toLong()
        val replyTexts = paths.map { File(it).readText().trim() }.filter(String::isNotEmpty)
        check(replyTexts.isNotEmpty()) { "no reply text" }

        val outputDirectory = File(context.filesDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val wavFile = File(outputDirectory, "$label.wav")
        val jsonFile = File(outputDirectory, "$label.json")

        val lane = KokoroEtDebugLane(
            File(context.filesDir, MODEL_DIRECTORY),
            VOICE_NAME,
            NativeKokoroPhonemizer(context.filesDir, "en-us"),
        )
        val recorder = ChunkWavRecorder()
        val timed = FirstWriteSink(recorder)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Native ExecuTorch calls run on one dedicated thread, as in DebugTtsBaselineService.
        val nativeDispatcher = Executors.newSingleThreadExecutor { Thread(it, "debug-kokoro-run").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val replyReports = JSONArray()
        val initMs = try {
            withContext(nativeDispatcher) { lane.initialize() }.also { Log.i(TAG, "kokoro lane initialized in ${it} ms") }
        } catch (error: Throwable) {
            withContext(nativeDispatcher) { lane.close() }
            nativeDispatcher.close()
            scope.cancel()
            throw error
        }
        try {
            replyTexts.forEachIndexed { replyIndex, text ->
                Log.i(TAG, "kokoro reply $replyIndex start")
                lane.resetCancellation()
                timed.firstWriteNs = 0L
                val startedAt = System.nanoTime()
                val writesBefore = recorder.replies.size
                val stream = VoiceReplyStream<String>(
                    engine = "Kokoro",
                    spokenText = text,
                    generation = replyIndex.toLong(),
                    isCurrent = { true },
                    unavailable = null,
                    chunker = { raw, onSkip ->
                        try {
                            lane.splitText(raw).asSequence()
                        } catch (error: Exception) {
                            onSkip(error)
                            emptySequence()
                        }
                    },
                    synthesize = { chunk ->
                        withContext(nativeDispatcher) {
                            Log.i(TAG, "kokoro chunk synth start chars=${chunk.length}")
                            lane.synthesizeChunkAudio(chunk).also { Log.i(TAG, "kokoro chunk synth done samples=${it.size}") }
                        }
                    },
                    sink = timed,
                    sampleRate = KokoroTts.SAMPLE_RATE_HZ,
                    hooks = NoHooks,
                    gain = 1f,
                    pitch = 1f,
                    scope = scope,
                )
                var stopRequestNs = 0L
                val appendJob = scope.launch { stream.append(text, isFinal = true) }
                val stopJob = stopAfterMs?.let { delayMs ->
                    scope.launch {
                        delay(delayMs)
                        if (appendJob.isActive) {
                            stopRequestNs = System.nanoTime()
                            stream.abort()
                        }
                    }
                }
                appendJob.join()
                val stopReturnNs = System.nanoTime()
                stopJob?.cancel()
                Log.i(TAG, "kokoro reply $replyIndex append joined; awaiting playback")
                stream.awaitPlayback()
                val endNs = System.nanoTime()
                val record = recorder.replies.getOrNull(writesBefore)
                replyReports.put(
                    JSONObject()
                        .put("index", replyIndex)
                        .put("chars", text.length)
                        .put(
                            "first_chunk_ms",
                            if (timed.firstWriteNs == 0L) JSONObject.NULL else millis(timed.firstWriteNs - startedAt),
                        )
                        .put("total_ms", millis(endNs - startedAt))
                        .put("chunk_writes", record?.chunks?.size ?: 0)
                        .put("start_frame", record?.startFrame ?: JSONObject.NULL)
                        .put("end_frame", record?.endFrame ?: JSONObject.NULL)
                        .put("stop_requested", stopRequestNs != 0L)
                        .put(
                            "stop_latency_ms",
                            if (stopRequestNs == 0L) JSONObject.NULL else millis(stopReturnNs - stopRequestNs),
                        )
                        .put("playback", playbackLabel(stream.playbackResult())),
                )
            }
        } finally {
            scope.cancel()
            withContext(nativeDispatcher) { lane.close() }
            nativeDispatcher.close()
        }

        SampleWav.writeFile(wavFile, recorder.samples(), KokoroTts.SAMPLE_RATE_HZ)
        val report = JSONObject()
            .put("schema", SCHEMA)
            .put("status", "ok")
            .put("label", label)
            .put("lane", "kokoro_et")
            .put("voice", VOICE_NAME)
            .put("init_ms", initMs)
            .put("stop_after_ms", stopAfterMs ?: JSONObject.NULL)
            .put("sample_rate_hz", KokoroTts.SAMPLE_RATE_HZ)
            .put("total_frames", recorder.frames)
            .put("total_ms", recorder.frames * 1000.0 / KokoroTts.SAMPLE_RATE_HZ)
            .put("wav", wavFile.absolutePath)
            .put("replies", replyReports)
        jsonFile.writeText(report.toString(2))
        return jsonFile
    }

    private fun millis(nanos: Long): Double = nanos / 1_000_000.0

    private fun playbackLabel(result: VoiceOutputResult): String = when (result) {
        VoiceOutputResult.Spoken -> "Spoken"
        is VoiceOutputResult.Unavailable -> "Unavailable: ${result.message}"
    }

    /** Records when the first waveform reaches the track; delegates everything else to [recorder]. */
    private class FirstWriteSink(private val recorder: ChunkWavRecorder) : VoicePcmSink {
        @Volatile var firstWriteNs: Long = 0L

        override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack {
            val inner = recorder.open(sampleRate, pitch)
            return object : VoicePcmTrack by inner {
                override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) {
                    if (firstWriteNs == 0L) firstWriteNs = System.nanoTime()
                    inner.write(samples, gain, isCurrent)
                }
            }
        }

        override fun silenceActive() = recorder.silenceActive()
    }

    private object NoHooks : VoiceReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) = Unit
        override fun onPlaybackEnded(generation: Long) = Unit
    }

    private companion object {
        const val TAG = "KernelAI"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_LABEL = "label"
        const val EXTRA_STOP_AFTER_MS = "stop_after_ms"
        const val OUTPUT_DIRECTORY = "kokoro_chunk_wav"
        const val MODEL_DIRECTORY = "kokoro-et"
        const val VOICE_NAME = "af_heart"
        const val SCHEMA = "kokoro-chunk-wav/1"
        val running = AtomicBoolean(false)
    }
}
