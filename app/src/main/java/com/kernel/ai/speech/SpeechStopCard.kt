package com.kernel.ai.speech

/**
 * Decides which single party posts the speech Stop card (notification ID 1873, #1873/#1959).
 *
 * [show] promotes the foreground service, which then posts the card itself. Only when the OS denies
 * the start is the card posted directly. [hide] stops the service (deferred by the service gate if
 * promotion is pending) and cancels the card only when it was posted directly, so ID 1873 is never
 * replaced by a second notification and a deferred stop can't leave a stale card behind.
 */
internal class SpeechStopCard<T>(
    private val startForeground: (T) -> Boolean,
    private val postCard: (T) -> Unit,
    private val cancelCard: () -> Unit,
    private val stopForeground: () -> Unit,
) {
    private var postedWithoutForeground = false

    fun show(token: T) {
        postedWithoutForeground = !startForeground(token)
        if (postedWithoutForeground) postCard(token)
    }

    fun hide() {
        stopForeground()
        if (postedWithoutForeground) {
            postedWithoutForeground = false
            cancelCard()
        }
    }
}
