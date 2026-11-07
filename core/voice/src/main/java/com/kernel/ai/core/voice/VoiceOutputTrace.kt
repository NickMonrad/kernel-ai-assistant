package com.kernel.ai.core.voice

import android.util.Log

/**
 * Debug-only trace of what each voice output engine actually starts speaking, and when.
 *
 * Off by default; the app turns it on only in debug builds. Used by the #1736 showcase
 * capture runner, which reads `adb logcat -s KernelAI_TTS` to regenerate Jandal's spoken
 * replies with the same voice and place them at the moment playback started (Android does
 * not let other apps record USAGE_ASSISTANT audio).
 */
object VoiceOutputTrace {
    const val TAG = "KernelAI_TTS"

    @Volatile
    var enabled: Boolean = false

    internal fun line(engine: String, text: String): String =
        "speaking_started engine=$engine chars=${text.length} text=${text.replace(Regex("\\s+"), " ").trim()}"

    fun speakingStarted(engine: String, text: String) {
        if (enabled) Log.d(TAG, line(engine, text))
    }
}
