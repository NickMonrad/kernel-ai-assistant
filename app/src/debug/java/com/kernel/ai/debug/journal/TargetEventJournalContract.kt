package com.kernel.ai.debug.journal

/**
 * Debug-only ADB contract for the target event journal.
 *
 * Three actions:
 *
 * 1. `GET_SEQUENCE` — returns the current highest sequence number as
 *    `result_data`.  Zero means the journal is empty.
 *
 * 2. `WAIT_FOR_JOURNAL_EVENT` — waits up to `timeout_ms` for an event of
 *    type `event_type` that was recorded after `since_sequence`.
 *    Result code 0 = found, 1 = timeout, 2 = error.
 *    `result_data` contains the matching event as a compact JSON line when
 *    found, or an error description when not.
 *
 * 3. `GET_JOURNAL_SNAPSHOT` — returns all events with sequence >
 *    `since_sequence` as a JSON array (one object per line).
 *    Always succeeds; `result_data` is the JSON array (possibly empty `[]`).
 *    Result code is always 0.
 *
 * ## Timeout semantics
 *
 * The receiver checks once synchronously, then enters a bounded `wait` / poll
 * loop on the journal's intrinsic lock.  The supplied `timeout_ms` is the
 * maximum wall-clock time the receiver will block.  The default is 15 000 ms
 * and the minimum is 500 ms.
 *
 * ## Response format
 *
 * Events are serialised as compact JSON with the fields:
 * - `s` — sequence (Long)
 * - `m` — monotonicMs (Long)
 * - `w` — wallClockMs (Long, 0 if absent)
 * - `t` — type (String)
 * - `g` — generationId (Long, 0 if absent)
 * - `i` — sessionId (Long, 0 if absent)
 * - `d` — metadata (JSON object, empty `{}` if absent)
 *
 * Example: `{"s":1,"m":123456789,"w":1705300000000,"t":"STT_READY","g":1,"i":1,"d":{}}`
 */
internal object TargetEventJournalContract {
    const val ACTION_GET_SEQUENCE = "com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE"
    const val ACTION_WAIT_FOR_EVENT = "com.kernel.ai.debug.action.WAIT_FOR_JOURNAL_EVENT"
    const val ACTION_GET_SNAPSHOT = "com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT"

    const val EXTRA_SINCE_SEQUENCE = "since_sequence"
    const val EXTRA_EVENT_TYPE = "event_type"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"

    const val RESULT_OK = 0
    const val RESULT_TIMEOUT = 1
    const val RESULT_ERROR = 2

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MIN_TIMEOUT_MS = 500L

    /** Maps Long? → clamped timeout. */
    fun clampTimeout(ms: Long?): Long =
        (ms ?: DEFAULT_TIMEOUT_MS).coerceAtLeast(MIN_TIMEOUT_MS)
}
