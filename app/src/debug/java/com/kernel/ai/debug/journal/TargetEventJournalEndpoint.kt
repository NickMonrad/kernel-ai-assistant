package com.kernel.ai.debug.journal

import android.util.Log
import com.kernel.ai.core.voice.AcousticJournalBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class TargetEventJournalResponse(
    val code: Int,
    val data: String,
)

/** Shared implementation for the debug-only target journal machine interfaces. */
internal object TargetEventJournalEndpoint {
    private const val TAG = "TargetJournalEndpoint"
    private const val WAIT_WORKER_COUNT = 4

    private val waitExecutor = ThreadPoolExecutor(
        WAIT_WORKER_COUNT,
        WAIT_WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "target-journal-wait").also { it.isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val activeWaits = ConcurrentHashMap<String, AcousticEventWaitRegistration>()
    private val reservationLock = Any()

    @Volatile
    private var journal: AcousticEventJournal? = null

    fun sequence(): TargetEventJournalResponse = success(lazyJournal().currentSequence.toString())

    fun snapshot(sinceSequence: Long): TargetEventJournalResponse {
        TargetEventJournalContract.sinceSequenceError(sinceSequence)?.let {
            return error(it)
        }
        return success(AcousticJournalJson.serialiseSnapshot(lazyJournal().snapshotSince(sinceSequence)))
    }

    fun waitForEvent(
        requestId: String?,
        sinceSequence: Long,
        eventType: String?,
        timeoutMs: Long,
    ): TargetEventJournalResponse {
        val argumentError = TargetEventJournalContract.requestIdError(requestId)
            ?: TargetEventJournalContract.sinceSequenceError(sinceSequence)
            ?: TargetEventJournalContract.eventTypeError(eventType)
            ?: TargetEventJournalContract.timeoutError(timeoutMs)
        if (argumentError != null) return error(argumentError)

        val stableRequestId = requireNotNull(requestId)
        val stableEventType = requireNotNull(eventType)
        val registration = AcousticEventWaitRegistration()
        val reservationError = synchronized(reservationLock) {
            when {
                activeWaits.containsKey(stableRequestId) ->
                    TargetEventJournalContract.ERROR_DUPLICATE_REQUEST_ID
                activeWaits.size >= WAIT_WORKER_COUNT ->
                    TargetEventJournalContract.ERROR_ENDPOINT_BUSY
                else -> {
                    activeWaits[stableRequestId] = registration
                    null
                }
            }
        }
        if (reservationError != null) return error(reservationError)

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var task: java.util.concurrent.Future<TargetEventJournalResponse>? = null
        return try {
            val submitted = waitExecutor.submit<TargetEventJournalResponse> {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(
                    (deadlineNanos - System.nanoTime()).coerceAtLeast(0L),
                )
                when (
                    val result = registration.resolve(
                        lazyJournal().waitForEvent(
                            sinceSequence = sinceSequence,
                            eventType = stableEventType,
                            timeoutMs = remainingMs,
                            cancellation = registration.cancellation,
                        ),
                    )
                ) {
                    is AcousticEventWaitResult.Found -> success(
                        AcousticJournalJson.serialiseEvent(result.event),
                    )
                    AcousticEventWaitResult.TimedOut -> TargetEventJournalResponse(
                        TargetEventJournalContract.RESULT_TIMEOUT,
                        "timeout:$stableRequestId:${timeoutMs}ms",
                    )
                    AcousticEventWaitResult.Cancelled -> cancelled(stableRequestId)
                }
            }
            task = submitted
            submitted.get(timeoutMs + WAIT_COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            error(TargetEventJournalContract.ERROR_ENDPOINT_BUSY)
        } catch (failure: Exception) {
            registration.cancel()
            lazyJournal().cancelWait(registration.cancellation)
            task?.cancel(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "wait_error", failure)
            error(TargetEventJournalContract.ERROR_ENDPOINT)
        } finally {
            synchronized(reservationLock) {
                activeWaits.remove(stableRequestId, registration)
            }
        }
    }

    fun cancelWait(requestId: String?): TargetEventJournalResponse {
        TargetEventJournalContract.requestIdError(requestId)?.let {
            return error(it)
        }
        val stableRequestId = requireNotNull(requestId)
        val registration = activeWaits[stableRequestId]
        if (registration == null || !registration.cancel()) {
            return error(TargetEventJournalContract.ERROR_UNKNOWN_REQUEST_ID)
        }
        lazyJournal().cancelWait(registration.cancellation)
        return cancelled(stableRequestId)
    }

    private fun success(data: String) = TargetEventJournalResponse(
        TargetEventJournalContract.RESULT_OK,
        data,
    )

    private fun error(data: String) = TargetEventJournalResponse(
        TargetEventJournalContract.RESULT_ERROR,
        data,
    )

    private fun cancelled(requestId: String) = TargetEventJournalResponse(
        TargetEventJournalContract.RESULT_CANCELLED,
        "cancelled:$requestId",
    )

    internal fun replaceJournalForTest(replacement: AcousticEventJournal) {
        check(activeWaits.isEmpty())
        synchronized(this) {
            journal = replacement
            AcousticJournalBridge.install(replacement)
        }
    }

    internal fun activeWaitCountForTest(): Int = activeWaits.size

    /** Creates and installs the process-local journal on first access. */
    private fun lazyJournal(): AcousticEventJournal = journal ?: synchronized(this) {
        journal ?: AcousticEventJournal().also {
            journal = it
            AcousticJournalBridge.install(it)
            Log.i(TAG, "journal installed (capacity=${it.journalCapacity})")
        }
    }

    private const val WAIT_COMPLETION_GRACE_MS = 1_000L
}
