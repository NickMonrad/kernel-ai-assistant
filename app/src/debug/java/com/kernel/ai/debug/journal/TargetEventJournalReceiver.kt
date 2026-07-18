package com.kernel.ai.debug.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Legacy debug-only ordered-broadcast endpoint for non-blocking journal reads.
 *
 * Android serialises ordered broadcasts and imposes a receiver completion deadline, so
 * bounded waits and cancellation use [TargetEventJournalProvider]. Keeping waits out of
 * this receiver prevents one open wait from blocking every later control request.
 */
class TargetEventJournalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        controlExecutor.execute {
            val response = runCatching {
                when (intent.action) {
                    TargetEventJournalContract.ACTION_GET_SEQUENCE ->
                        TargetEventJournalEndpoint.sequence()
                    TargetEventJournalContract.ACTION_GET_SNAPSHOT ->
                        TargetEventJournalEndpoint.snapshot(
                            intent.getLongExtra(
                                TargetEventJournalContract.EXTRA_SINCE_SEQUENCE,
                                0L,
                            ),
                        )
                    else -> TargetEventJournalResponse(
                        TargetEventJournalContract.RESULT_ERROR,
                        "use_content_provider:${intent.action}",
                    )
                }
            }.getOrElse {
                Log.e(TAG, "receiver_error", it)
                TargetEventJournalResponse(
                    TargetEventJournalContract.RESULT_ERROR,
                    TargetEventJournalContract.ERROR_ENDPOINT,
                )
            }
            pendingResult.setResultCode(response.code)
            pendingResult.setResultData(response.data)
            pendingResult.finish()
        }
    }

    private companion object {
        const val TAG = "TargetJournalReceiver"
        val controlExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "target-journal-control").also { it.isDaemon = true }
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
