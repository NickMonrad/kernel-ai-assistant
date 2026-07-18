package com.kernel.ai.debug.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.AcousticJournalBridge
import java.util.concurrent.Executors

private const val TAG = "TargetJournalReceiver"

/**
 * Debug-only ADB endpoint for the structured target event journal.
 *
 * ## Lifecycle
 *
 * The receiver lazily creates and installs a [BoundedAcousticEventJournal] on
 * its first invocation.  The journal is shared with production hooks via
 * [AcousticJournalBridge].
 *
 * ## Invocation (examples)
 *
 * ```sh
 * # Get current highest sequence
 * adb shell am broadcast \
 *   -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
 *   -a com.kernel.ai.debug.action.GET_JOURNAL_SEQUENCE
 *
 * # Wait for STT_READY, timeout 10 seconds
 * adb shell am broadcast \
 *   -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
 *   -a com.kernel.ai.debug.action.WAIT_FOR_JOURNAL_EVENT \
 *   --el since_sequence 5 \
 *   --es event_type STT_READY \
 *   --el timeout_ms 10000
 *
 * # Get snapshot since sequence 5
 * adb shell am broadcast \
 *   -n com.kernel.ai.debug/com.kernel.ai.debug.journal.TargetEventJournalReceiver \
 *   -a com.kernel.ai.debug.action.GET_JOURNAL_SNAPSHOT \
 *   --el since_sequence 5
 * ```
 *
 * ## Thread safety
 *
 * All three actions are executed on the receiver's single-threaded executor
 * so they are serialised with respect to each other.  Journal recording
 * happens on production threads (detector, service, main) and synchronises
 * on the journal's intrinsic lock.
 */
class TargetEventJournalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        executor.execute {
            try {
                handle(intent, pendingResult)
            } catch (e: Exception) {
                Log.e(TAG, "receiver_error", e)
                pendingResult.setResultCode(TargetEventJournalContract.RESULT_ERROR)
                pendingResult.setResultData("receiver_error")
                pendingResult.finish()
            }
        }
    }

    private fun handle(
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        val journal = lazyJournal()

        when (intent.action) {
            TargetEventJournalContract.ACTION_GET_SEQUENCE -> {
                val seq = journal.currentSequence
                pendingResult.setResultCode(TargetEventJournalContract.RESULT_OK)
                pendingResult.setResultData(seq.toString())
                pendingResult.finish()
            }

            TargetEventJournalContract.ACTION_WAIT_FOR_EVENT -> {
                val sinceSeq = intent.getLongExtra(
                    TargetEventJournalContract.EXTRA_SINCE_SEQUENCE, 0L,
                )
                val eventType = intent.getStringExtra(
                    TargetEventJournalContract.EXTRA_EVENT_TYPE,
                )
                val timeoutMs = TargetEventJournalContract.clampTimeout(
                    intent.getLongExtra(
                        TargetEventJournalContract.EXTRA_TIMEOUT_MS,
                        TargetEventJournalContract.DEFAULT_TIMEOUT_MS,
                    ),
                )

                if (eventType.isNullOrBlank()) {
                    pendingResult.setResultCode(TargetEventJournalContract.RESULT_ERROR)
                    pendingResult.setResultData("event_type is required")
                    pendingResult.finish()
                    return
                }

                val event = journal.waitForEvent(sinceSeq, eventType, timeoutMs)
                if (event != null) {
                    pendingResult.setResultCode(TargetEventJournalContract.RESULT_OK)
                    pendingResult.setResultData(AcousticJournalJson.serialiseEvent(event))
                } else {
                    pendingResult.setResultCode(TargetEventJournalContract.RESULT_TIMEOUT)
                    pendingResult.setResultData(
                        "timeout after ${timeoutMs}ms waiting for $eventType since seq $sinceSeq"
                    )
                }
                pendingResult.finish()
            }

            TargetEventJournalContract.ACTION_GET_SNAPSHOT -> {
                val sinceSeq = intent.getLongExtra(
                    TargetEventJournalContract.EXTRA_SINCE_SEQUENCE, 0L,
                )
                val snapshot = journal.snapshotSince(sinceSeq)
                pendingResult.setResultCode(TargetEventJournalContract.RESULT_OK)
                pendingResult.setResultData(AcousticJournalJson.serialiseSnapshot(snapshot))
                pendingResult.finish()
            }

            else -> {
                pendingResult.setResultCode(TargetEventJournalContract.RESULT_ERROR)
                pendingResult.setResultData("unknown_action: ${intent.action}")
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "target-journal-receiver").also { it.isDaemon = true }
        }

        @Volatile
        private var journal: AcousticEventJournal? = null

        /**
         * Creates and installs the journal on first access.
         * Thread-safe: at most one journal is ever created.
         */
        private fun lazyJournal(): AcousticEventJournal {
            return journal ?: synchronized(this) {
                journal ?: AcousticEventJournal().also {
                    journal = it
                    AcousticJournalBridge.install(it)
                    Log.i(TAG, "journal installed (capacity=${it.journalCapacity})")
                }
            }
        }

    }
}

internal object AcousticJournalJson {
    fun serialiseSnapshot(snapshot: AcousticJournalSnapshot): String =
        buildString {
            append("""{"lowestSequence":${snapshot.lowestSequence},"highestSequence":${snapshot.highestSequence},"overflowed":${snapshot.overflowed},"events":[""")
            snapshot.events.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append(serialiseEvent(event))
            }
            append("]}")
        }

    fun serialiseEvent(event: com.kernel.ai.core.voice.AcousticEvent): String {
        val metaJson = if (event.metadata.isEmpty()) "{}" else {
            event.metadata.entries.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) { (key, value) ->
                "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
            }
        }
        return """{"s":${event.sequence},"m":${event.monotonicMs},"w":${event.wallClockMs},"t":"${escapeJson(event.type)}","g":${event.generationId},"i":${event.sessionId},"d":$metaJson}"""
    }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
}
