package com.kernel.ai.assistant

import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.AcousticJournalBridge

/** Emits one correlated wake-session stream with exactly one terminal event. */
internal class WakeSessionJournal(
    private val generationId: Long,
    private val sessionId: Long,
    private val emit: (
        type: String,
        generationId: Long,
        sessionId: Long,
        metadata: () -> Map<String, String>,
    ) -> Unit = { type, generationId, sessionId, metadata ->
        AcousticJournalBridge.record(type, generationId, sessionId, metadata = metadata)
    },
) {
    private var started = false
    private var terminal = false

    @Synchronized
    fun start() {
        check(!started) { "Wake session already started" }
        started = true
        emit(AcousticEventType.VOICE_SESSION_STARTED, generationId, sessionId, EMPTY_METADATA)
    }

    @Synchronized
    fun record(
        type: String,
        metadata: () -> Map<String, String> = EMPTY_METADATA,
    ) {
        if (!started || terminal) return
        emit(type, generationId, sessionId, metadata)
    }

    @Synchronized
    fun complete(): Boolean = finish(AcousticEventType.SESSION_COMPLETED, EMPTY_METADATA)

    @Synchronized
    fun cancel(category: String): Boolean = finish(
        AcousticEventType.SESSION_CANCELLED,
        { mapOf("category" to category) },
    )

    private fun finish(type: String, metadata: () -> Map<String, String>): Boolean {
        if (!started || terminal) return false
        terminal = true
        emit(type, generationId, sessionId, metadata)
        return true
    }

    private companion object {
        val EMPTY_METADATA: () -> Map<String, String> = { emptyMap() }
    }
}
