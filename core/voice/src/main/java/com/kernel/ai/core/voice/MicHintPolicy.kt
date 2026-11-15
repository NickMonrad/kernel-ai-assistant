package com.kernel.ai.core.voice

/** First-use mic hint policy (#1850): shown for the first [MAX_HINT_USES] mic starts, then never again. */
object MicHintPolicy {
    const val MAX_HINT_USES = 5

    /** [useCount] is the persisted count returned by [VoiceInputPreferences.recordMicUseStarted]. */
    fun shouldShowHint(useCount: Int): Boolean = useCount in 1..MAX_HINT_USES
}
