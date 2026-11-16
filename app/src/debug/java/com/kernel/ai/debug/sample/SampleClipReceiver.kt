package com.kernel.ai.debug.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: renders the setup sample line with the selected Sherpa voice (Cori) to
 * `files/sample_cori.wav`, using the controller's own warm-up and its private generate handles. Nothing
 * in production code changes; playback is not started.
 *
 *   adb -s <serial> shell am broadcast -n ai.jandal.assistant.debug/com.kernel.ai.debug.sample.SampleClipReceiver \
 *       --es text "Kia ora, I'm Jandle. What can I help with?"
 */
class SampleClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val text = intent.getStringExtra("text") ?: DEFAULT_TEXT
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val controller = EntryPointAccessors.fromApplication(
                    appContext,
                    SampleClipEntryPoint::class.java,
                ).sherpaController()
                val warm = controller.warmUp()
                check(warm !is VoiceOutputResult.Unavailable) { "warmUp: $warm" }
                val (samples, rate) = render(controller, text)
                val file = File(appContext.filesDir, "sample_cori.wav")
                writeWav(file, samples, rate)
                Log.i(TAG, "wrote ${file.absolutePath} samples=${samples.size} rate=$rate")
            } catch (e: Exception) {
                Log.e(TAG, "sample render failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Calls the controller's private `ttsInstance.generate(text, 0, 1.0f)` and reads GeneratedAudio. */
    private fun render(controller: SherpaOnnxVoiceOutputController, text: String): Pair<FloatArray, Int> {
        val ttsField = SherpaOnnxVoiceOutputController::class.java.getDeclaredField("ttsInstance")
            .also { it.isAccessible = true }
        val genField = SherpaOnnxVoiceOutputController::class.java.getDeclaredField("generateMethod")
            .also { it.isAccessible = true }
        val tts = checkNotNull(ttsField.get(controller)) { "Sherpa TTS instance missing after warm-up" }
        val gen = checkNotNull(genField.get(controller)) { "generate handle missing after warm-up" } as java.lang.reflect.Method
        val audio = checkNotNull(gen.invoke(tts, text, 0, 1.0f)) { "generate returned null" }
        val samples = audio.javaClass.getMethod("getSamples").invoke(audio) as FloatArray
        val rate = audio.javaClass.getMethod("getSampleRate").invoke(audio) as Int
        return samples to rate
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val dataBytes = samples.size * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLe(36 + dataBytes)
            out.writeBytes("WAVEfmt ")
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(rate)
            out.writeIntLe(rate * 2)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.writeBytes("data")
            out.writeIntLe(dataBytes)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                out.writeShortLe(v)
            }
        }
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
        write((v shr 16) and 0xff)
        write((v shr 24) and 0xff)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
    }

    private fun DataOutputStream.writeBytes(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    companion object {
        private const val TAG = "SampleClipReceiver"
        private const val DEFAULT_TEXT = "Kia ora, I'm Jandle. What can I help with?"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SampleClipEntryPoint {
    fun sherpaController(): SherpaOnnxVoiceOutputController
}
