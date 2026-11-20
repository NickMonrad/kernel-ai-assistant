package com.kernel.ai.core.voice.asr4all.host

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kernel.ai.core.voice.asr4all.Asr4AllEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllModelConfig
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import java.io.File
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Host-side parity harness (#2004): runs the real Kotlin engine on the real `.pte` exports
 * through [PythonBridgeProgram] and writes one JSON line per clip × size × tier with the
 * partial-text sequence and the final text, for a separate verifier to compare against
 * reference outputs. Skipped unless `ASR4ALL_HARNESS_CONFIG` names a config file; see
 * `tools/asr4all-cleanroom/README.md`.
 */
class Asr4AllHostHarnessTest {
    @Test
    fun `run host harness`() {
        val configPath = System.getenv("ASR4ALL_HARNESS_CONFIG")
        assumeTrue(!configPath.isNullOrBlank(), "ASR4ALL_HARNESS_CONFIG not set")
        val configFile = File(configPath!!).absoluteFile
        val base = configFile.parentFile
        val cfg = JsonParser.parseString(configFile.readText()).asJsonObject
        fun path(p: String) = File(p).let { if (it.isAbsolute) it else File(base, p) }

        val python = cfg["python"].asString
        val bridge = path(cfg["bridge"].asString)
        val outFile = path(cfg["output"].asString).also { it.parentFile?.mkdirs() }
        val stderrLog = File(outFile.path + ".bridge.log")
        val loader = PythonBridgeProgram.loader(python, bridge, stderrLog)
        val chunkMs = cfg["chunk_ms"]?.asInt ?: 100
        val repeat = cfg["repeat"]?.asInt ?: 1
        // Optional second chunk size: the final text must not depend on how audio is chunked.
        val checkChunkMs = cfg["check_chunk_ms"]?.asInt
        val clips = cfg["clips"].asJsonArray.map { it.asJsonObject.let { c -> c["id"].asString to path(c["wav"].asString) } }
        val gson = GsonBuilder().disableHtmlEscaping().create()

        outFile.bufferedWriter().use { writer ->
            for (sizeName in cfg["sizes"].asJsonArray.map { it.asString }) {
                val size = Asr4AllSize.valueOf(sizeName)
                val modelDir = path(cfg["models"].asJsonObject[sizeName].asString)
                val tierNames = cfg["tiers"]?.asJsonArray?.map { it.asString }
                    ?.takeUnless { it == listOf("all") }
                    ?: Asr4AllModelConfig.load(modelDir, size).tiers.keys.toList()
                for (tierName in tierNames) {
                    Asr4AllEngine.open(modelDir, size, loader, tierName).use { engine ->
                        val chunk = engine.config.sampleRate * chunkMs / 1000
                        for ((id, wav) in clips) {
                            val pcm = readWav(wav, engine.config.sampleRate)
                            val runs = (1..repeat).map { runClip(engine, pcm, chunk) }
                            runs.drop(1).forEach { assertEquals(runs[0], it, "non-deterministic output for $id $sizeName $tierName") }
                            if (checkChunkMs != null) {
                                val other = runClip(engine, pcm, engine.config.sampleRate * checkChunkMs / 1000).second
                                assertEquals(runs[0].second, other, "final text depends on chunking for $id $sizeName $tierName")
                            }
                            val (events, final) = runs[0]
                            val row = JsonObject().apply {
                                addProperty("clip", id)
                                addProperty("wav_sha256", sha256(wav))
                                addProperty("size", sizeName)
                                addProperty("tier", tierName)
                                addProperty("chunk_samples", chunk)
                                add("partials", gson.toJsonTree(events.map { it.second }))
                                add("partial_sample_offsets", gson.toJsonTree(events.map { it.first }))
                                addProperty("final", final)
                                addProperty("repeats_identical", repeat)
                            }
                            writer.write(gson.toJson(row))
                            writer.newLine()
                            writer.flush()
                        }
                    }
                }
            }
        }
    }

    /** Feeds [pcm] in [chunk]-sample pieces; returns (sample offset, partial) at each change, and the final text. */
    private fun runClip(engine: Asr4AllEngine, pcm: ShortArray, chunk: Int): Pair<List<Pair<Int, String>>, String> {
        val events = ArrayList<Pair<Int, String>>()
        var last = ""
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(chunk, pcm.size - offset)
            val partial = engine.acceptPcm16(pcm, offset, n)
            offset += n
            if (partial != last) {
                events += offset to partial
                last = partial
            }
        }
        return events to engine.finish()
    }

    private fun readWav(file: File, sampleRate: Int): ShortArray {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val f = stream.format
            require(
                f.encoding == AudioFormat.Encoding.PCM_SIGNED && f.sampleSizeInBits == 16 && f.channels == 1 &&
                    f.sampleRate.toInt() == sampleRate && !f.isBigEndian,
            ) { "$file: need 16-bit mono little-endian PCM at $sampleRate Hz, got $f" }
            val bytes = stream.readAllBytes()
            return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
