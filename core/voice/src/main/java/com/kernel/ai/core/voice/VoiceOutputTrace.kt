package com.kernel.ai.core.voice

import android.util.Log

/**
 * Trace of what each voice output engine starts speaking, and when.
 *
 * The `speaking_started` lines are debug-only: they are written only when [enabled] is set
 * (debug builds) and the `KernelAI_TTS` log property is on. The epoch-stamped `SpeakingStarted`
 * line from [logSpeakingStarted] carries no text and is always logged.
 *
 * Used by the #1736 showcase capture runner, which reads `adb logcat -s KernelAI_TTS` to
 * regenerate Jandal's spoken replies with the same voice and place them at the moment
 * playback started (Android does not let other apps record USAGE_ASSISTANT audio).
 *
 * One line per chunk, logged when that chunk starts playing, so a streamed reply gives one
 * line per sentence with its own start time. Both gates must be open for the text lines:
 * - [enabled], which the app sets only in debug builds;
 * - the log property, which only the capture runner sets
 *   (`adb shell setprop log.tag.KernelAI_TTS DEBUG`). An ordinary debug build therefore never
 *   writes spoken text to logcat, so it can't end up in a shared diagnostic export.
 */
object VoiceOutputTrace {
    const val TAG = "KernelAI_TTS"

    /** logcat drops the tail of entries over ~4 KB; longer text is split into numbered parts. */
    internal const val MAX_TEXT_PER_LINE = 3000

    @Volatile
    var enabled: Boolean = false

    /** Test seam; production writes to logcat. */
    @Volatile
    internal var sink: ((String) -> Unit)? = null

    /** Test seam for [logSpeakingStarted]; production writes to logcat. */
    @Volatile
    internal var startSink: ((String) -> Unit)? = null

    /**
     * `speaking_started engine=<e> chars=<n> part=<i>/<k> text=<text>`, where `chars` is the
     * length of the whole (whitespace-collapsed) text and the parts join back to it.
     */
    internal fun lines(engine: String, text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val parts = if (clean.isEmpty()) listOf("") else clean.chunked(MAX_TEXT_PER_LINE)
        return parts.mapIndexed { i, part ->
            "speaking_started engine=$engine chars=${clean.length} part=${i + 1}/${parts.size} text=$part"
        }
    }

    fun speakingStarted(engine: String, text: String) {
        if (!enabled) return
        val testSink = sink
        if (testSink != null) {
            lines(engine, text).forEach(testSink)
        } else if (Log.isLoggable(TAG, Log.DEBUG)) {
            lines(engine, text).forEach { Log.d(TAG, it) }
        }
    }

    /**
     * Epoch-stamped `KernelAI` log at the moment playback starts (#1872). Carries only the engine
     * and character count, never the text, so it is safe in ordinary logs and measurable with
     * `adb logcat -v epoch -s KernelAI`.
     */
    fun logSpeakingStarted(engine: String, chars: Int) {
        val line = "SpeakingStarted engine=$engine chars=$chars epochMs=${System.currentTimeMillis()}"
        val testSink = startSink
        if (testSink != null) testSink(line) else Log.i("KernelAI", line)
    }
}
