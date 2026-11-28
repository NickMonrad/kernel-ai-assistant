package com.kernel.ai.debug.tts.kokoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.kokoro.KokoroDebugRender
import com.kernel.ai.core.voice.kokoro.KokoroVoice
import com.kernel.ai.debug.sample.SampleWav
import com.kernel.ai.debug.tts.ChunkWavRecorder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (#2175): renders reply text to a WAV through Kokoro's text path ([KokoroDebugRender]) with Emma and
 * the device's gain and pitch, using [ChunkWavRecorder] in place of AudioTrack.
 *
 * Extras: `paths` = `|`-separated absolute, app-readable text files, one reply each, rendered in order;
 * `label` = output name. Output: `files/kokoro_chunk_wav/<label>.wav` and `<label>.json`. Explicit component only;
 * the manifest entry requires DUMP (adb shell).
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
            } catch (error: Exception) {
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
        val replyTexts = paths.map { File(it).readText().trim() }.filter(String::isNotEmpty)
        check(replyTexts.isNotEmpty()) { "no reply text" }

        val prefs = EntryPointAccessors.fromApplication(context, KokoroChunkWavEntryPoint::class.java)
            .voiceOutputPreferences()
        val gain = prefs.voiceGain.first()
        val pitch = prefs.voicePitch.first()

        val outputDirectory = File(context.filesDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val wavFile = File(outputDirectory, "$label.wav")
        val jsonFile = File(outputDirectory, "$label.json")

        val recorder = ChunkWavRecorder()
        val replyReports = JSONArray()
        replyTexts.forEachIndexed { replyIndex, text ->
            val startFrame = recorder.frames
            val chunks = KokoroDebugRender.render(context, KokoroVoice.EMMA, text, recorder, gain, pitch)
            replyReports.put(
                JSONObject()
                    .put("index", replyIndex)
                    .put("chars", text.length)
                    .put("start_frame", startFrame)
                    .put("end_frame", recorder.frames)
                    .put("chunks", JSONArray(chunks)),
            )
        }

        SampleWav.writeFile(wavFile, recorder.samples(), KokoroDebugRender.SAMPLE_RATE_HZ)
        val report = JSONObject()
            .put("schema", SCHEMA)
            .put("status", "ok")
            .put("label", label)
            .put("sample_rate_hz", KokoroDebugRender.SAMPLE_RATE_HZ)
            .put("gain", gain.toDouble())
            .put("pitch", pitch.toDouble())
            .put("voice", KokoroVoice.EMMA.name)
            .put("total_frames", recorder.frames)
            .put("total_ms", recorder.frames * 1000.0 / KokoroDebugRender.SAMPLE_RATE_HZ)
            .put("wav", wavFile.absolutePath)
            .put("replies", replyReports)
        jsonFile.writeText(report.toString(2))
        return jsonFile
    }

    private companion object {
        const val TAG = "KernelAI"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_LABEL = "label"
        const val OUTPUT_DIRECTORY = "kokoro_chunk_wav"
        const val SCHEMA = "kokoro-chunk-wav/1"
        val running = AtomicBoolean(false)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface KokoroChunkWavEntryPoint {
    fun voiceOutputPreferences(): VoiceOutputPreferences
}
