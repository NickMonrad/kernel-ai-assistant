package com.kernel.ai.core.voice

import java.util.concurrent.ConcurrentHashMap

/**
 * Audio session ids of Jandal's own speech-to-text microphone captures (#2109).
 *
 * The STT controllers record on [android.media.MediaRecorder.AudioSource.MIC]. The wake service must
 * recognise these recording clients as Jandal's own, so a voice session never pauses wake capture as if
 * another app were recording. Each controller adds its session id when its AudioRecord is created and
 * removes it before the AudioRecord is released.
 */
object OwnMicCaptureSessions {
    private val ids: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun add(sessionId: Int) {
        ids += sessionId
    }

    fun remove(sessionId: Int) {
        ids -= sessionId
    }

    fun snapshot(): Set<Int> = ids.toSet()
}
