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
 * Platform seam for resolving the package that currently owns the foreground (#1502).
 *
 * Transitions cannot answer this after a service or process restart: a camera that has been in
 * front since before the service started produced no recent transition at all, so the current
 * state has to come from usage aggregation instead (see [UsageStatsCurrentForegroundSource]).
 */
fun interface ForegroundPackageSource {
    /** The package in front of the user, or null when the platform cannot say. */
    fun currentForegroundPackage(): String?
}

/**
 * Resolves which package is in front of the user from a stream of [ForegroundEvent]s: the last
 * package to enter the foreground wins, and an exit clears it only if that package was the one
 * held. Holds nothing but the current foreground package name (#1502).
 */
class ForegroundPackageTracker(private val targetPackage: String) {

    private var foregroundPackage: String? = null

    /** Applies [events] in order. Returns true when the tracked target's foreground state changed. */
    fun apply(events: List<ForegroundEvent>): Boolean {
        val before = isTargetForeground
        for (event in events) {
            when (event.transition) {
                ForegroundTransition.ENTER ->
                    if (foregroundPackage != event.packageName) foregroundPackage = event.packageName

                ForegroundTransition.EXIT ->
                    if (foregroundPackage == event.packageName) foregroundPackage = null
            }
        }
        return before != isTargetForeground
    }

    /** True when [targetPackage] currently owns the foreground. */
    val isTargetForeground: Boolean get() = foregroundPackage == targetPackage

    /**
     * Starts from a package known to be in front right now, without replaying history (#1502).
     * Used when a monitor starts while the target has already been foreground for a long time.
     */
    fun seed(packageName: String?) {
        foregroundPackage = packageName
    }
}

/**
 * #1502: watches for [targetPackage] becoming/leaving the foreground app and reports the
 * transitions to [onCameraEntered] / [onCameraExited].
 *
 * Invariants:
 * - **State before capture.** [run] first establishes the current foreground package from
 *   [currentForegroundPackage] and reports it, so a caller that arms wake capture only in response
 *   to [onCameraExited] can never take the microphone while the target is already in front —
 *   including a service/process restart after the target has been foreground for arbitrarily long,
 *   which no transition window can see. While that state cannot be read, nothing is reported and
 *   the monitor retries on the next cadence.
 * - **Transitions only.** Callbacks fire on a change of the target's foreground state, never once
 *   per poll, so repeated events cannot duplicate a pause/resume operation.
 * - **Failure isolated.** A failing query or a failing callback is logged and retried on the next
 *   cadence; neither can terminate monitoring. Cancellation always propagates.
 * - **Fail-safe termination.** Monitoring ends only after [onUnavailable] completes; a cleanup
 *   that threw is retried on the next cadence rather than dropping the fail-safe silently.
 * - **Privacy minimal.** Only the current foreground package name is held in memory, for the
 *   lifetime of the poll window. Nothing is persisted, exported, transmitted, or logged — the
 *   only package name that may appear in a log is [targetPackage].
 * - **Off the main thread.** [run] suspends; callers must launch it on a background dispatcher.
 */
class CameraForegroundMonitor(
    private val source: ForegroundEventSource,
    private val targetPackage: String,
    private val pollIntervalMs: Long,
    private val currentForegroundPackage: ForegroundPackageSource,
    private val isReady: () -> Boolean = { true },
    private val onCameraEntered: suspend () -> Unit,
    private val onCameraExited: suspend () -> Unit,
    private val onUnavailable: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val tracker = ForegroundPackageTracker(targetPackage)
    private var appliedTargetForeground = false
    private var windowStart = 0L
    private var seeded = false

    /**
     * Polls until cancelled. Returns early — after [onUnavailable] succeeds — when [isReady]
     * reports that the capability the workaround depends on is gone.
     */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!isReady()) {
                // Without Usage Access Jandal can no longer protect the camera: clean up and stop
                // polling rather than spinning against a revoked capability. The cleanup has to
                // complete before monitoring ends — it is the only thing that releases the
                // microphone and stops the service — so a failed attempt is retried, not dropped.
                if (releaseAndStop()) return
            } else if (seeded) {
                val now = clock()
                if (poll(windowStart, now)) windowStart = now
                reconcile()
            } else if (seed()) {
                reconcile(force = true)
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Establishes the current foreground state, in place of replaying a transition window, and
     * starts the event window at the moment it was resolved. Returns false — to be retried on the
     * next cadence — while the platform cannot say which package is in front.
     */
    private fun seed(): Boolean {
        val current = try {
            currentForegroundPackage.currentForegroundPackage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "current foreground package could not be read; retrying next tick", e)
            return false
        }
        if (current == null) {
            Log.w(TAG, "no current foreground package reported; retrying next tick")
            return false
        }
        tracker.seed(current)
        windowStart = clock()
        seeded = true
        return true
    }

    /** Returns true when the unavailable cleanup completed, so monitoring may end. */
    private suspend fun releaseAndStop(): Boolean = try {
        onUnavailable()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "camera-coexistence cleanup failed; retrying next tick", e)
        false
    }

    /** Returns true when the window was consumed, so the next poll can start after it. */
    private fun poll(windowStart: Long, now: Long): Boolean = try {
        tracker.apply(source.eventsBetween(windowStart, now))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Recoverable: keep the unconsumed window so the events are re-read on the next cadence.
        Log.w(TAG, "foreground query failed; retrying next tick", e)
        false
    }

    /**
     * Pushes the current target state if it differs from what was last applied, or unconditionally
     * when [force] is set so the caller learns the state the monitor started from. The applied
     * state is recorded only after the callback succeeds, so a failed transition is retried on the
     * next cadence instead of leaving the monitor believing it suspended when it did not.
     */
    private suspend fun reconcile(force: Boolean = false) {
        val desired = tracker.isTargetForeground
        if (!force && desired == appliedTargetForeground) return
        try {
            if (desired) onCameraEntered() else onCameraExited()
            appliedTargetForeground = desired
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "foreground transition callback failed; retrying next tick", e)
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

/**
 * How far back [UsageStatsCurrentForegroundSource] reads usage aggregation. The range only has to
 * reach the moment the package now in front became visible; a day covers every foreground session
 * a device that sleeps can produce, and the query returns one aggregated row per package per day
 * bucket rather than raw events.
 */
private const val CURRENT_FOREGROUND_RANGE_MS = 24L * 60 * 60 * 1000

/**
 * Resolves the package in front of the user from usage aggregation (requires Usage Access).
 *
 * A transition query cannot answer this when the current package has been foreground for longer
 * than the queried window — the window then contains no event for it at all — so the state comes
 * from the aggregated `lastTimeVisible` timestamps instead: the package that became visible most
 * recently is the one in front. Verified on the affected BKQ-N49: a camera in front for over
 * 100 s is the most recently visible package, and the package that takes over from it replaces it
 * within the monitor's cadence. Nothing is retained beyond the resolved package name.
 */
class UsageStatsCurrentForegroundSource(private val context: Context) : ForegroundPackageSource {

    override fun currentForegroundPackage(): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - CURRENT_FOREGROUND_RANGE_MS,
            now,
        ) ?: return null
        return mostRecentlyVisiblePackage(stats.map { it.packageName to it.lastTimeVisible })
    }
}

/**
 * The package that became visible most recently, or null when no package reports a visible
 * timestamp — an unanswerable state, which callers must treat as "cannot confirm the foreground".
 */
internal fun mostRecentlyVisiblePackage(visibleSince: List<Pair<String, Long>>): String? {
    var candidate: String? = null
    var newest = 0L
    for ((packageName, visibleAt) in visibleSince) {
        if (visibleAt > newest) {
            newest = visibleAt
            candidate = packageName
        }
    }
    return candidate
}
