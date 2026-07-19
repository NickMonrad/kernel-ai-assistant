package com.kernel.ai.debug.journal

import com.kernel.ai.core.voice.AcousticEventType

/**
 * Debug-only ADB contract for the target event journal.
 *
 * The concurrent machine interface is exposed through `ContentProvider.call` at
 * [PROVIDER_AUTHORITY]. `GET_JOURNAL_SEQUENCE` returns the highest sequence as
 * decimal result data. `GET_JOURNAL_SNAPSHOT` accepts optional `since_sequence`
 * (default 0) and returns the exact envelope
 * `{"lowestSequence":Long,"highestSequence":Long,"overflowed":Boolean,"events":[Event...]}`.
 *
 * `WAIT_FOR_JOURNAL_EVENT` requires `request_id` and `event_type`; it accepts
 * optional `since_sequence` and `timeout_ms`. `request_id` is 1–64 ASCII
 * letters, digits, `.`, `_`, or `-`, and must be unique among active waits.
 *
 * `GET_JOURNAL_WAIT_STATUS` requires the same `request_id` and returns
 * `active:<request_id>` only after the wait is registered. `CANCEL_JOURNAL_WAIT`
 * requires the same `request_id`. A successful cancellation and the cancelled wait
 * both return code 3 with `cancelled:<request_id>`.
 *
 * Result codes: 0 success/event found/active, 1 timeout, 2 deterministic argument or
 * endpoint error, 3 cancelled. Timeouts default to 15 000 ms and must be within
 * the inclusive 500–60 000 ms range. Waits use journal notifications, not polling.
 *
 * Stable argument errors are:
 * `argument_error:missing_request_id`, `argument_error:invalid_request_id`,
 * `argument_error:duplicate_request_id`, `argument_error:unknown_request_id`,
 * `argument_error:negative_since_sequence`, `argument_error:missing_event_type`,
 * `argument_error:invalid_event_type`, and `argument_error:invalid_timeout_ms`.
 *
 * Events use compact fields `s` sequence, `m` monotonic ms, `w` wall-clock ms,
 * `t` type, `g` generation ID, `i` session ID, and `d` metadata.
 */
internal object TargetEventJournalContract {
    const val ACTION_GET_SEQUENCE = "com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE"
    const val ACTION_GET_SNAPSHOT = "com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT"

    const val PROVIDER_AUTHORITY = "com.kernel.ai.debug.target-event-journal"
    const val PROVIDER_URI = "content://$PROVIDER_AUTHORITY"
    const val METHOD_GET_SEQUENCE = "GET_JOURNAL_SEQUENCE"
    const val METHOD_WAIT_FOR_EVENT = "WAIT_FOR_JOURNAL_EVENT"
    const val METHOD_GET_WAIT_STATUS = "GET_JOURNAL_WAIT_STATUS"
    const val METHOD_CANCEL_WAIT = "CANCEL_JOURNAL_WAIT"
    const val METHOD_GET_SNAPSHOT = "GET_JOURNAL_SNAPSHOT"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_SINCE_SEQUENCE = "since_sequence"
    const val EXTRA_EVENT_TYPE = "event_type"
    const val EXTRA_TIMEOUT_MS = "timeout_ms"
    const val RESULT_CODE = "result_code"
    const val RESULT_DATA = "result_data"

    const val RESULT_OK = 0
    const val RESULT_TIMEOUT = 1
    const val RESULT_ERROR = 2
    const val RESULT_CANCELLED = 3

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MIN_TIMEOUT_MS = 500L
    const val MAX_TIMEOUT_MS = 60_000L

    const val ERROR_MISSING_REQUEST_ID = "argument_error:missing_request_id"
    const val ERROR_INVALID_REQUEST_ID = "argument_error:invalid_request_id"
    const val ERROR_DUPLICATE_REQUEST_ID = "argument_error:duplicate_request_id"
    const val ERROR_UNKNOWN_REQUEST_ID = "argument_error:unknown_request_id"
    const val ERROR_NEGATIVE_SINCE_SEQUENCE = "argument_error:negative_since_sequence"
    const val ERROR_MISSING_EVENT_TYPE = "argument_error:missing_event_type"
    const val ERROR_INVALID_EVENT_TYPE = "argument_error:invalid_event_type"
    const val ERROR_INVALID_TIMEOUT = "argument_error:invalid_timeout_ms"
    const val ERROR_ENDPOINT_BUSY = "endpoint_busy"
    const val ERROR_ENDPOINT = "endpoint_error"

    private val requestIdPattern = Regex("[A-Za-z0-9._-]{1,64}")

    fun requestIdError(requestId: String?): String? = when {
        requestId.isNullOrBlank() -> ERROR_MISSING_REQUEST_ID
        !requestIdPattern.matches(requestId) -> ERROR_INVALID_REQUEST_ID
        else -> null
    }

    fun sinceSequenceError(sequence: Long): String? =
        ERROR_NEGATIVE_SINCE_SEQUENCE.takeIf { sequence < 0L }

    fun eventTypeError(eventType: String?): String? = when {
        eventType.isNullOrBlank() -> ERROR_MISSING_EVENT_TYPE
        eventType !in AcousticEventType.ALL -> ERROR_INVALID_EVENT_TYPE
        else -> null
    }

    fun timeoutError(timeoutMs: Long): String? =
        ERROR_INVALID_TIMEOUT.takeIf { timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS }
}
