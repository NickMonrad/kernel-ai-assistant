package com.kernel.ai.debug.inflect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import com.kernel.ai.core.voice.InflectMicroTextFrontend
import com.kernel.ai.core.voice.InflectPhonemizer
import com.kernel.ai.core.voice.InflectReplyHooks
import com.kernel.ai.core.voice.InflectReplyStream
import com.kernel.ai.core.voice.SpeechCardText
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.debug.sample.SampleWav
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (#2096): renders reply text to a WAV through the production reply pipeline, [InflectReplyStream]
 * (normalize, speech chunks, lazy phonemize, one decode per chunk, edge fades, voice gain and pitch from the
 * device's preferences), with [InflectChunkWavRecorder] standing in for AudioTrack. Each reply is one
 * InflectReplyStream; replies are concatenated with no gap, and the JSON records every chunk's sample offsets.
 *
 * Extras: `paths` = `|`-separated absolute, app-readable text files, one reply each, rendered in order;
 * `label` = output name. Output: `files/inflect_chunk_wav/<label>.wav` and `<label>.json`; the result data is the
 * JSON path. Explicit component only; the manifest entry requires DUMP (adb shell).
 */
class InflectChunkWavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "inflect chunk wav: a render is already active")
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val json = render(appContext, intent)
                Log.i(TAG, "inflect chunk wav done path=${json.absolutePath}")
            } catch (error: Exception) {
                Log.e(TAG, "inflect chunk wav failed", error)
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
        val replyTexts = paths.map { File(it).readText().trim() }.filter(String::isNotEmpty)
        check(replyTexts.isNotEmpty()) { "no reply text" }

        val prefs = EntryPointAccessors.fromApplication(context, InflectChunkWavEntryPoint::class.java)
            .voiceOutputPreferences()
        val voice = prefs.selectedSherpaVoice.first()
        val gain = prefs.voiceGain.first()
        val pitch = prefs.voicePitch.first()
        val dataDirectory = File(voice.voiceDir(context), ESPEAK_DATA_DIR)
        check(dataDirectory.isDirectory) { "selected voice ${voice.name} has no $ESPEAK_DATA_DIR" }
        val modelDirectory = InflectMicroModelSpec.modelDirectory(context)

        val outputDirectory = File(context.filesDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val wavFile = File(outputDirectory, "$label.wav")
        val jsonFile = File(outputDirectory, "$label.json")

        val runner = InflectMicroOnnxRunner(modelDirectory)
        val recorder = InflectChunkWavRecorder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val replyReports = JSONArray()
        try {
            replyTexts.forEachIndexed { replyIndex, text ->
                val normalized = InflectMicroTextFrontend.normalize(SpeechCardText.speakable(text))
                val speechChunks = InflectMicroTextFrontend.speechChunks(normalized)
                val replyCountBefore = recorder.replies.size
                val stream = InflectReplyStream(
                    spokenText = text,
                    generation = replyIndex.toLong(),
                    isCurrent = { true },
                    unavailable = null,
                    synthesize = { phonemes ->
                        runner.resetCancellation()
                        runner.synthesize(phonemes).waveform
                    },
                    phonemizeChunk = { chunkText ->
                        InflectMicroTextFrontend.applyPhonemeOverrides(
                            InflectPhonemizer.phonemize(chunkText, dataDirectory),
                        )
                    },
                    sink = recorder,
                    hooks = NoHooks,
                    gain = gain,
                    pitch = pitch,
                    scope = scope,
                )
                val appended = stream.append(text, isFinal = true)
                stream.awaitPlayback()
                check(appended !is VoiceOutputResult.Unavailable) { "reply $replyIndex unavailable: $appended" }
                check(stream.playbackResult() !is VoiceOutputResult.Unavailable) {
                    "reply $replyIndex playback failed: ${stream.playbackResult()}"
                }
                replyReports.put(replyReport(replyIndex, text, normalized, speechChunks, recorder, replyCountBefore))
            }
        } finally {
            scope.cancel()
            runner.close()
        }

        SampleWav.writeFile(wavFile, recorder.samples(), InflectMicroOnnxRunner.SAMPLE_RATE_HZ)
        val report = JSONObject()
            .put("schema", SCHEMA)
            .put("status", "ok")
            .put("label", label)
            .put("sample_rate_hz", InflectMicroOnnxRunner.SAMPLE_RATE_HZ)
            .put("gain", gain.toDouble())
            .put("pitch", pitch.toDouble())
            .put("voice", voice.name)
            .put("total_frames", recorder.frames)
            .put("total_ms", recorder.frames * 1000.0 / InflectMicroOnnxRunner.SAMPLE_RATE_HZ)
            .put("wav", wavFile.absolutePath)
            .put("replies", replyReports)
        jsonFile.writeText(report.toString(2))
        return jsonFile
    }

    private fun replyReport(
        replyIndex: Int,
        text: String,
        normalized: String,
        speechChunks: List<String>,
        recorder: InflectChunkWavRecorder,
        replyCountBefore: Int,
    ): JSONObject {
        // A reply that produced no audio has no record; a reply with audio opens exactly one track.
        val record = recorder.replies.getOrNull(replyCountBefore)
        val chunks = record?.chunks.orEmpty()
        val piecesMatch = chunks.size == speechChunks.size
        val chunkArray = JSONArray()
        chunks.forEach { chunk ->
            chunkArray.put(
                JSONObject()
                    .put("index", chunk.index)
                    .put("start_frame", chunk.startFrame)
                    .put("end_frame", chunk.endFrame)
                    .put("frames", chunk.endFrame - chunk.startFrame)
                    .put("text", if (piecesMatch) speechChunks[chunk.index] else JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("index", replyIndex)
            .put("chars", text.length)
            .put("normalized_chars", normalized.length)
            .put("speech_chunks", speechChunks.size)
            .put("chunk_writes", chunks.size)
            .put("chunk_text_mapped", piecesMatch)
            .put("start_frame", record?.startFrame ?: JSONObject.NULL)
            .put("end_frame", record?.endFrame ?: JSONObject.NULL)
            .put("chunks", chunkArray)
    }

    private object NoHooks : InflectReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) = Unit
        override fun onPlaybackEnded(generation: Long) = Unit
    }

    private companion object {
        const val TAG = "KernelAI"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_LABEL = "label"
        const val OUTPUT_DIRECTORY = "inflect_chunk_wav"
        const val ESPEAK_DATA_DIR = "espeak-ng-data"
        const val SCHEMA = "inflect-chunk-wav/1"
        val running = AtomicBoolean(false)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface InflectChunkWavEntryPoint {
    fun voiceOutputPreferences(): VoiceOutputPreferences
}
