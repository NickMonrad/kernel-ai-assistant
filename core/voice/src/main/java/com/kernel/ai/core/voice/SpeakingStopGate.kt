package com.kernel.ai.core.voice

/**
 * Pairs each reply's SpeakingStarted with at most one SpeakingStopped (#1873 SF1).
 *
 * Native synthesis cannot be cancelled, so a reply's unwind can run after the next reply has
 * already emitted SpeakingStarted. Only the token of the reply that last emitted SpeakingStarted
 * may claim the stop; a stale unwind claims nothing and emits nothing.
 */
internal class SpeakingStopGate {
    private var startedToken: Long = NO_TOKEN

    @Synchronized
    fun markStarted(token: Long) {
        startedToken = token
    }

    /** True exactly once, and only for the token that last started. */
    @Synchronized
    fun claimStopped(token: Long): Boolean {
        if (startedToken != token) return false
        startedToken = NO_TOKEN
        return true
    }

    private companion object {
        const val NO_TOKEN = Long.MIN_VALUE
    }
}
