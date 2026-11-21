package com.kernel.ai.core.voice.asr4all

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue

/**
 * Model files and clips for the asr4all instrumentation tests.
 *
 * Models are not committed or bundled: push the unmodified FUTO files (pinned revisions in
 * docs/evidence/2004/CLEANROOM.md) into the test APK's app-specific external directory, keeping
 * the repository layout, for example:
 *
 * ```
 * adb -s <serial> push asr4all-m/. /sdcard/Android/data/com.kernel.ai.core.voice.test/files/asr4all/m/
 * ```
 *
 * with `config.json`, `release_geom.json`, `preprocessor_config.json` and
 * `executorch/xnnpack_int8/{metadata.json,asr_encoder.pte}` under each size directory. Tests that
 * need a size whose files are absent are skipped, not failed.
 */
object Asr4AllDeviceFiles {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun modelDir(size: Asr4AllSize): File {
        val root = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)) { "no external files dir" }
        val dir = File(root, "asr4all/${size.name.lowercase()}")
        assumeTrue(
            "asr4all ${size.name} files not pushed to ${dir.path}",
            File(dir, "${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte").isFile,
        )
        return dir
    }

    /** 16-bit mono PCM samples of a committed WAV asset (`assets/asr4all/<name>`). */
    fun clip(name: String): ShortArray {
        val bytes = instrumentation.context.assets.open("asr4all/$name").use { it.readBytes() }
        // Canonical 44-byte RIFF header: PCM, mono, 16-bit, 16 kHz (checked below).
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 36, 4) == "data") { "$name is not a canonical WAV" }
        val channels = (bytes[22].toInt() and 0xff) or (bytes[23].toInt() shl 8)
        val rate = (bytes[24].toInt() and 0xff) or ((bytes[25].toInt() and 0xff) shl 8) or ((bytes[26].toInt() and 0xff) shl 16)
        val bits = bytes[34].toInt() and 0xff
        check(channels == 1 && rate == 16000 && bits == 16) { "$name: need 16 kHz mono 16-bit, got $rate Hz × $channels × $bits" }
        val n = (bytes.size - 44) / 2
        return ShortArray(n) { i -> ((bytes[44 + 2 * i].toInt() and 0xff) or (bytes[45 + 2 * i].toInt() shl 8)).toShort() }
    }

    fun text(name: String): String =
        instrumentation.context.assets.open("asr4all/$name").bufferedReader().use { it.readText() }

    /** Feeds [pcm] in [chunk]-sample pieces; returns each distinct non-empty partial in order. */
    fun feed(engine: Asr4AllEngine, pcm: ShortArray, chunk: Int = 1600): List<String> {
        val partials = ArrayList<String>()
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(chunk, pcm.size - offset)
            val partial = engine.acceptPcm16(pcm, offset, n)
            if (partial.isNotEmpty() && partial != partials.lastOrNull()) partials += partial
            offset += n
        }
        return partials
    }

    /** [feed] then [Asr4AllEngine.finish]: the partials seen and the final text. */
    fun transcribe(engine: Asr4AllEngine, pcm: ShortArray, chunk: Int = 1600): Pair<List<String>, String> =
        feed(engine, pcm, chunk) to engine.finish()
}
