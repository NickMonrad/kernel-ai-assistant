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
 * The broadcast result is 0 on success, 1 when the render fails (the message carries the exception), and 2 when
 * `text` is missing. `am broadcast` prints it as `Broadcast completed: result=<code>, data="<message>"`.
 *
 *   adb -s <serial> shell am broadcast -n ai.jandal.assistant.debug/com.kernel.ai.debug.tts.kokoro.KokoroRenderReceiver \
 *       --es text "Mon 23 Nov: 27°C, 92% rain" --es out weather
 */
class KokoroRenderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // goAsync gives a background broadcast about 60 s (10 s for a foreground one) before the system may kill the
        // process. Model load plus a long render can exceed that. Accepted for a debug tool; do not use it for timing.
        val pendingResult = goAsync()
        val text = intent.getStringExtra(EXTRA_TEXT)
        val out = intent.getStringExtra(EXTRA_OUT) ?: "render"
        if (text.isNullOrBlank()) {
            Log.e(TAG, "missing --es $EXTRA_TEXT")
            pendingResult.setResult(RESULT_MISSING_TEXT, "missing --es $EXTRA_TEXT", null)
            pendingResult.finish()
            return
        }
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
                val message = "wrote ${file.absolutePath} samples=${samples.size} " +
                    "durationS=${"%.3f".format(samples.size.toDouble() / KokoroTts.SAMPLE_RATE_HZ)}"
                Log.i(TAG, message)
                pendingResult.setResult(RESULT_OK, message, null)
            } catch (e: Throwable) {
                // Includes UnsatisfiedLinkError when libkokoro_phonemis.so is absent: log, do not crash the process.
                Log.e(TAG, "kokoro render failed", e)
                pendingResult.setResult(RESULT_FAILED, "kokoro render failed: $e", null)
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
        const val RESULT_OK = 0
        const val RESULT_FAILED = 1
        const val RESULT_MISSING_TEXT = 2
    }
}
