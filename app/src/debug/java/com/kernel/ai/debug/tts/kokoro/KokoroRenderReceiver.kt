package com.kernel.ai.debug.tts.kokoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.debug.sample.SampleWav
import java.io.File

/**
 * Debug-only (#2115 listening checks): renders one text through the Kokoro ExecuTorch lane, the same path the
 * baseline lane takes (SpeechCardText, the shared number normaliser, phonemis, Kokoro). Nothing is played.
 * Writes `files/kokoro_render_<out>.wav` (24 kHz mono) and logs `spoken text="..."` under tag KokoroRenderReceiver.
 *
 *   adb -s <serial> shell am broadcast -n ai.jandal.assistant.debug/com.kernel.ai.debug.tts.kokoro.KokoroRenderReceiver \
 *       --es text "Mon 23 Nov: 27°C, 92% rain" --es out weather
 */
class KokoroRenderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        val out = intent.getStringExtra(EXTRA_OUT) ?: "render"
        if (text.isNullOrBlank()) {
            Log.e(TAG, "missing --es $EXTRA_TEXT")
            return
        }
        val pendingResult = goAsync()
        val filesDir = context.filesDir
        Thread({
            var phonemizer: NativeKokoroPhonemizer? = null
            var lane: KokoroEtDebugLane? = null
            try {
                require(out.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "out must be [A-Za-z0-9_-]{1,64}" }
                phonemizer = NativeKokoroPhonemizer(filesDir, "en-us")
                val created = KokoroEtDebugLane(File(filesDir, "kokoro-et"), VOICE, phonemizer)
                lane = created
                created.initialize()
                Log.i(TAG, "spoken text=\"${created.spokenText(text)}\"")
                val samples = created.renderSamples(text)
                val file = File(filesDir, "kokoro_render_$out.wav")
                SampleWav.writeFile(file, samples, KokoroTts.SAMPLE_RATE_HZ)
                Log.i(
                    TAG,
                    "wrote ${file.absolutePath} samples=${samples.size} " +
                        "durationS=${"%.3f".format(samples.size.toDouble() / KokoroTts.SAMPLE_RATE_HZ)}",
                )
            } catch (e: Throwable) {
                // Includes UnsatisfiedLinkError when libkokoro_phonemis.so is absent: log, do not crash the process.
                Log.e(TAG, "kokoro render failed", e)
            } finally {
                lane?.close()
                phonemizer?.close()
                pendingResult.finish()
            }
        }, "kokoro-render").start()
    }

    private companion object {
        const val TAG = "KokoroRenderReceiver"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OUT = "out"
        const val VOICE = "af_heart"
    }
}
