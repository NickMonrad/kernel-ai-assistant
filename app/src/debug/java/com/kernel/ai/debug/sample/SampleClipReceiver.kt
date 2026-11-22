package com.kernel.ai.debug.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only: renders text with the selected Piper voice through the same steps as real speech
 * (`SherpaOnnxVoiceOutputController.speak`): `PronunciationLexicon.ensureLoaded`,
 * `SherpaSpeechText.prepare`, `generate(prepared, sid, speed)` with the stored speed, then the stored gain
 * applied as in playback. Nothing in production code changes; playback is not started.
 *
 * The lexicon object and `SherpaSpeechText` are `internal` to `:core:voice`, so they are called reflectively.
 * The prepared text is logged under tag `SampleClipReceiver` as `prepared text="..."`.
 *
 *   adb -s <serial> shell am broadcast -n ai.jandal.assistant.debug/com.kernel.ai.debug.sample.SampleClipReceiver \
 *       --es text "Ka pai tō mahi." --es out mahi
 *
 * Writes `files/sample_<out>.wav` (default `out` is the voice name).
 */
class SampleClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val text = intent.getStringExtra("text") ?: DEFAULT_TEXT
        val outName = intent.getStringExtra("out")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(appContext, SampleClipEntryPoint::class.java)
                val controller = entryPoint.sherpaController()
                val prefs = entryPoint.voiceOutputPreferences()
                val voice = prefs.selectedSherpaVoice.first()
                val speed = prefs.sherpaSpeed.first()
                val gain = prefs.voiceGain.first()
                val warm = controller.warmUp()
                check(warm !is VoiceOutputResult.Unavailable) { "warmUp: $warm" }
                ensureLexiconLoaded(appContext)
                val spoken = prepareSpokenText(text)
                Log.i(TAG, "prepared text=\"$spoken\" input=\"$text\" speed=$speed gain=$gain sid=0")
                val (raw, rate) = render(controller, spoken, speed)
                val samples = SampleWav.applyGain(raw, gain)
                val file = File(appContext.filesDir, "sample_${outName ?: voice.name.lowercase()}.wav")
                SampleWav.writeFile(file, samples, rate)
                Log.i(
                    TAG,
                    "wrote ${file.absolutePath} samples=${samples.size} rate=$rate " +
                        "durationS=${"%.3f".format(samples.size.toDouble() / rate)}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "sample render failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun ensureLexiconLoaded(context: Context) {
        val cls = Class.forName("com.kernel.ai.core.voice.PronunciationLexicon")
        val instance = cls.getField("INSTANCE").get(null)
        cls.getMethod("ensureLoaded", Context::class.java).invoke(instance, context)
    }

    private fun prepareSpokenText(text: String): String {
        val cls = Class.forName("com.kernel.ai.core.voice.SherpaSpeechText")
        val instance = cls.getField("INSTANCE").get(null)
        // One-shot text: the default chunkEnd = true, as SherpaOnnxVoiceOutputController.speak uses.
        return cls.getMethod("prepare", String::class.java, java.lang.Boolean.TYPE)
            .invoke(instance, text, true) as String
    }

    /** Calls the controller's private `ttsInstance.generate(text, sid, speed)` and reads GeneratedAudio. */
    private fun render(controller: SherpaOnnxVoiceOutputController, text: String, speed: Float): Pair<FloatArray, Int> {
        val ttsField = SherpaOnnxVoiceOutputController::class.java.getDeclaredField("ttsInstance")
            .also { it.isAccessible = true }
        val genField = SherpaOnnxVoiceOutputController::class.java.getDeclaredField("generateMethod")
            .also { it.isAccessible = true }
        val tts = checkNotNull(ttsField.get(controller)) { "Sherpa TTS instance missing after warm-up" }
        val gen = checkNotNull(genField.get(controller)) { "generate handle missing after warm-up" } as java.lang.reflect.Method
        val audio = checkNotNull(gen.invoke(tts, text, 0, speed)) { "generate returned null" }
        val samples = audio.javaClass.getMethod("getSamples").invoke(audio) as FloatArray
        val rate = audio.javaClass.getMethod("getSampleRate").invoke(audio) as Int
        return samples to rate
    }

    companion object {
        private const val TAG = "SampleClipReceiver"
        private const val DEFAULT_TEXT = "Kee-ora, I'm Jandle. What can I help with?"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SampleClipEntryPoint {
    fun sherpaController(): SherpaOnnxVoiceOutputController
    fun voiceOutputPreferences(): VoiceOutputPreferences
}
