package com.kernel.ai.debug.journal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Debug-only concurrent ADB endpoint for bounded journal waits and control calls.
 *
 * Content-provider calls execute on Binder threads rather than Android's serial ordered-
 * broadcast queue. Independent waits can therefore remain open while snapshot, sequence,
 * and cancellation calls complete promptly, without a BroadcastReceiver ANR deadline.
 */
class TargetEventJournalProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        TargetEventJournalEndpoint.sequence()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val response = when (method) {
            TargetEventJournalContract.METHOD_GET_SEQUENCE -> TargetEventJournalEndpoint.sequence()
            TargetEventJournalContract.METHOD_GET_SNAPSHOT -> TargetEventJournalEndpoint.snapshot(
                extras.longExtra(TargetEventJournalContract.EXTRA_SINCE_SEQUENCE, 0L),
            )
            TargetEventJournalContract.METHOD_WAIT_FOR_EVENT -> TargetEventJournalEndpoint.waitForEvent(
                requestId = extras?.getString(TargetEventJournalContract.EXTRA_REQUEST_ID),
                sinceSequence = extras.longExtra(TargetEventJournalContract.EXTRA_SINCE_SEQUENCE, 0L),
                eventType = extras?.getString(TargetEventJournalContract.EXTRA_EVENT_TYPE),
                timeoutMs = extras.longExtra(
                    TargetEventJournalContract.EXTRA_TIMEOUT_MS,
                    TargetEventJournalContract.DEFAULT_TIMEOUT_MS,
                ),
            )
            TargetEventJournalContract.METHOD_GET_WAIT_STATUS -> TargetEventJournalEndpoint.waitStatus(
                extras?.getString(TargetEventJournalContract.EXTRA_REQUEST_ID),
            )
            TargetEventJournalContract.METHOD_CANCEL_WAIT -> TargetEventJournalEndpoint.cancelWait(
                extras?.getString(TargetEventJournalContract.EXTRA_REQUEST_ID),
            )
            else -> TargetEventJournalResponse(
                TargetEventJournalContract.RESULT_ERROR,
                "unknown_method:$method",
            )
        }
        return Bundle(2).apply {
            putInt(TargetEventJournalContract.RESULT_CODE, response.code)
            putString(TargetEventJournalContract.RESULT_DATA, response.data)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("Use ContentProvider.call")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use ContentProvider.call")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use ContentProvider.call")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Use ContentProvider.call")

    private fun Bundle?.longExtra(key: String, defaultValue: Long): Long =
        if (this?.containsKey(key) == true) getLong(key) else defaultValue
}
