package com.kernel.ai.debug.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1557 step 2: E4B-resident coexistence run in one process. Order: load E4B via the production
 * [InferenceEngine], Kokoro corpus pass 1, one chat turn, Kokoro corpus pass 2. VmHWM and the resolved
 * LLM backend are recorded at each stage. Debug-only; writes `files/kokoro_coexist/<label>.json`.
 * Extras: `corpus_path`, `kokoro_path`, `gemma_path`, `label`.
 */
class KokoroCoexistenceService : Service() {
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "kokoro coexist already running; ignoring start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val extras = Intent(intent ?: Intent())
        Thread({
            try {
                execute(extras)
            } finally {
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }, "kokoro-coexist").start()
        return START_NOT_STICKY
    }

    private fun execute(intent: Intent) {
        val label = intent.getStringExtra("label")?.takeIf { it.isNotBlank() } ?: "coexist"
        val out = File(filesDir, "kokoro_coexist/$label.json").also { it.parentFile?.mkdirs() }
        val stages = JSONArray()
        var engine: InferenceEngine? = null
        var lane: KokoroTasksAudioDebugLane? = null
        try {
            val corpus = DebugTtsCorpus.parse(File(requireNotNull(intent.getStringExtra("corpus_path"))))
            val kokoroFile = File(requireNotNull(intent.getStringExtra("kokoro_path")))
            val gemmaPath = requireNotNull(intent.getStringExtra("gemma_path"))
            val entry = EntryPointAccessors.fromApplication(applicationContext, KokoroCoexistEntryPoint::class.java)
            engine = entry.inferenceEngine()

            stage(stages, "start")
            runBlocking { engine.initialize(ModelConfig(modelPath = gemmaPath)) }
            stage(stages, "e4b_loaded", extra = JSONObject().put("llm_backend", engine.activeBackend.value?.name ?: "null"))

            val created = KokoroTasksAudioDebugLane(kokoroFile, cacheDirectory = File(filesDir, "kokoro_cache"))
            lane = created
            val initMs = created.initialize()
            stage(stages, "kokoro_loaded", extra = JSONObject().put("runner_init_ms", initMs))

            val pass1 = corpusPass(created, corpus)
            stage(stages, "kokoro_pass1", extra = pass1)

            val reply = runBlocking { engine.generateOnce("In one sentence, what is the capital of New Zealand?", thinkingEnabled = false) }
            stage(stages, "chat_turn", extra = JSONObject().put("reply_chars", reply.length))

            val pass2 = corpusPass(created, corpus)
            stage(stages, "kokoro_pass2", extra = pass2)

            stage(stages, "end")
            writeJson(out, JSONObject().put("schema", "kokoro-coexist/1").put("status", "ok")
                .put("label", label).put("lane", created.laneId).put("backend", created.backend)
                .put("llm_backend_final", engine.activeBackend.value?.name ?: "null")
                .put("stages", stages))
            Log.i(TAG, "kokoro coexist done path=${out.absolutePath}")
        } catch (error: Throwable) {
            Log.e(TAG, "kokoro coexist failed", error)
            stage(stages, "failed", extra = JSONObject().put("error", "${error::class.java.simpleName}: ${error.message}"))
            writeJson(out, JSONObject().put("schema", "kokoro-coexist/1").put("status", "failed")
                .put("label", label).put("stages", stages))
        } finally {
            lane?.close()
            runBlocking { runCatching { engine?.shutdown() } }
        }
    }

    private fun corpusPass(lane: DebugTtsLane, corpus: List<DebugTtsSample>): JSONObject {
        val rows = JSONArray()
        for (sample in corpus) {
            val first = longArrayOf(-1L)
            val utterance = lane.synthesize(sample.text) { index, elapsed -> if (index == 0) first[0] = elapsed }
            rows.put(JSONObject()
                .put("id", sample.id)
                .put("first_chunk_ms", first[0])
                .put("synth_ms", utterance.synthMs)
                .put("audio_ms", utterance.audioMs)
                .put("rtf", if (utterance.audioMs > 0) utterance.synthMs / utterance.audioMs else -1.0)
                .put("finite", utterance.finite))
        }
        return JSONObject().put("rows", rows).put("vmhwm_kb", vmKb())
    }

    private fun stage(stages: JSONArray, name: String, extra: JSONObject = JSONObject()) {
        stages.put(extra.put("stage", name).put("vmhwm_kb", vmKb()).put("vmrss_kb", vmKb("VmRSS")))
    }

    private fun vmKb(key: String = "VmHWM"): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull() ?: -1L

    private fun writeJson(file: File, json: JSONObject) = file.writeText(json.toString(2))

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Debug Kokoro coexistence", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Debug Kokoro coexistence running")
        .setContentText("E4B resident, Kokoro corpus and chat turn")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "KernelAI"
        private const val CHANNEL_ID = "debug_kokoro_coexist"
        private const val NOTIFICATION_ID = 1557
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface KokoroCoexistEntryPoint {
    fun inferenceEngine(): InferenceEngine
}
