package com.kernel.ai.core.voice.asr4all

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The asr4all engine on the real M export with the real ExecuTorch runtime (#2004 AC2, AC3).
 *
 * Clip: LibriSpeech dev-clean `84-121550-0015` (CC BY 4.0; OpenSLR 12 `dev-clean.tar.gz`, decoded
 * with `ffmpeg -ac 1 -ar 16000 -c:a pcm_s16le -bitexact`; SHA-256 `71a4d201…39df4`), one of the
 * verifier's AC1 clips. The expected final text in
 * `assets/asr4all/84-121550-0015.m.stream_c16r4.final.txt` is the verifier's reference output for
 * M at `stream_c16r4` (PR #2011, AC1 comment), as #2004 AC2 requires.
 */
@RunWith(AndroidJUnit4::class)
class Asr4AllEngineDeviceTest {
    private val clip = "84-121550-0015.wav"

    @Test
    fun mediumExportTranscribesThePublicClip() {
        val pcm = Asr4AllDeviceFiles.clip(clip)
        val expected = Asr4AllDeviceFiles.text("84-121550-0015.m.stream_c16r4.final.txt").trim()

        Asr4AllEngine.open(Asr4AllDeviceFiles.modelDir(Asr4AllSize.M), Asr4AllSize.M).use { engine ->
            assertEquals("stream_c16r4", engine.tier.name)
            val started = System.nanoTime()
            val (partials, final) = Asr4AllDeviceFiles.transcribe(engine, pcm)
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "M stream_c16r4 ${pcm.size / 16} ms audio in $ms ms; ${partials.size} partials; final: $final")
            partials.forEach { Log.i(TAG, "partial: $it") }

            assertEquals(expected, final)
            assertTrue("no live partials", partials.isNotEmpty())
            // Partials are corrected text too: cased from the first word.
            assertTrue(partials.first(), partials.first().first().isUpperCase())

            // Same input on the same device, after finish() reset the utterance: same output.
            assertEquals(partials to final, Asr4AllDeviceFiles.transcribe(engine, pcm))
        }
    }

    @Test
    fun programFromAnotherSizeIsATypedConfigErrorNotACrash() {
        // M's config files next to S's program: the 1.5.1 runtime would abort the process on the
        // first method call, so the engine must reject this before the runtime runs anything.
        val m = Asr4AllDeviceFiles.modelDir(Asr4AllSize.M)
        val s = Asr4AllDeviceFiles.modelDir(Asr4AllSize.S)
        val mixed = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "asr4all-mixed").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for (name in listOf("config.json", "release_geom.json", "preprocessor_config.json", "${Asr4AllModelConfig.EXPORT_DIR}/metadata.json")) {
                File(m, name).copyTo(File(mixed, name))
            }
            File(s, "${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte").copyTo(File(mixed, "${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte"))

            try {
                Asr4AllEngine.open(mixed, Asr4AllSize.M).close()
                fail("opened M config files with S's program")
            } catch (e: Asr4AllConfigException) {
                Log.i(TAG, "mixed files rejected: ${e.message}")
                assertTrue(e.message, e.message!!.contains("stream_c16r4"))
            }

            // The runtime is still usable afterwards.
            Asr4AllEngine.open(m, Asr4AllSize.M).close()
        } finally {
            mixed.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "Asr4AllDeviceTest"
    }
}
