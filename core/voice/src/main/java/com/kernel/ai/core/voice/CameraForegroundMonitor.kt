package com.kernel.ai.core.voice

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TAG = "CameraMonitor"

/** Whether a usage event put a package in front of the user or took it away. */
enum class ForegroundTransition { ENTER, EXIT }

/**
 * One foreground-activity transition, decoupled from [UsageEvents] so tracking logic stays
 * testable without the platform.
 */
data class ForegroundEvent(val transition: ForegroundTransition, val packageName: String?)

/** Platform seam for reading recent foreground-activity transitions (#1502). */
fun interface ForegroundEventSource {
    fun eventsBetween(startMillis: Long, endMillis: Long): List<ForegroundEvent>
}

/**
 * #1502: watches for [targetPackage] becoming/leaving the foreground app and reports the
 * transitions to [onCameraEntered] / [onCameraExited].
 *
 * Invariants:
 * - **Transitions only.** Callbacks fire on a change of the target's foreground state, never once
 *   per poll, so repeated events cannot duplicate a pause/resume operation.
 * - **Failure isolated.** A failing query or a failing callback is logged and retried on the next
 *   cadence; neither can terminate monitoring. Cancellation always propagates.
 * - **Privacy minimal.** Only the current foreground package name is held in memory, for the
 *   lifetime of the poll window. Nothing is persisted, exported, transmitted, or logged — the
 *   only package name that may appear in a log is [targetPackage].
 * - **Off the main thread.** [run] suspends; callers must launch it on a background dispatcher.
 *
 * The initial poll reads a bounded [initialLookbackMillis] window so a monitor that starts while
 * the target is already foreground (for example a service restart) still reports it; every later
 * poll reads only the window since the previous poll.
 */
class CameraForegroundMonitor(
    private val source: ForegroundEventSource,
    private val targetPackage: String,
    private val pollIntervalMs: Long,
    private val initialLookbackMillis: Long,
    private val isReady: () -> Boolean = { true },
    private val onCameraEntered: suspend () -> Unit,
    private val onCameraExited: suspend () -> Unit,
    private val onUnavailable: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var foregroundPackage: String? = null
    private var appliedTargetForeground = false

    /**
     * Polls until cancelled. Returns early — after [onUnavailable] — when [isReady] reports that
     * the capability the workaround depends on is gone.
     */
    suspend fun run() {
        var windowStart = clock() - initialLookbackMillis
        while (currentCoroutineContext().isActive) {
            if (!isReady()) {
                // Without Usage Access Jandal can no longer protect the camera: report once and
                // stop polling rather than spinning against a revoked capability.
                guard { onUnavailable() }
                return
            }
            val now = clock()
            if (poll(windowStart, now)) windowStart = now
            reconcile()
            delay(pollIntervalMs)
        }
    }

    /** Returns true when the window was consumed, so the next poll can start after it. */
    private fun poll(windowStart: Long, now: Long): Boolean = try {
        for (event in source.eventsBetween(windowStart, now)) {
            when (event.transition) {
                ForegroundTransition.ENTER ->
                    if (foregroundPackage != event.packageName) foregroundPackage = event.packageName

                ForegroundTransition.EXIT ->
                    if (foregroundPackage == event.packageName) foregroundPackage = null
            }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Recoverable: keep the unconsumed window so the events are re-read on the next cadence.
        Log.w(TAG, "foreground query failed; retrying next tick", e)
        false
    }

    /**
     * Pushes the current target state if it differs from what was last applied. The applied state
     * is recorded only after the callback succeeds, so a failed transition is retried on the next
     * cadence instead of leaving the monitor believing it suspended when it did not.
     */
    private suspend fun reconcile() {
        val desired = foregroundPackage == targetPackage
        if (desired == appliedTargetForeground) return
        try {
            if (desired) onCameraEntered() else onCameraExited()
            appliedTargetForeground = desired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "foreground transition callback failed; retrying next tick", e)
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "foreground transition callback failed", e)
        }
    }
}

/** Reads foreground-activity transitions from [UsageStatsManager] (requires Usage Access). */
class UsageStatsForegroundEventSource(private val context: Context) : ForegroundEventSource {

    override fun eventsBetween(startMillis: Long, endMillis: Long): List<ForegroundEvent> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val usageEvents = manager.queryEvents(startMillis, endMillis) ?: return emptyList()
        // Events are converted immediately and the platform event object is reused; nothing is
        // retained or copied beyond the transition and package name.
        val transitions = ArrayList<ForegroundEvent>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val transition = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                -> ForegroundTransition.ENTER

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                -> ForegroundTransition.EXIT

                else -> null
            } ?: continue
            transitions += ForegroundEvent(transition, event.packageName)
        }
        return transitions
    }
}
