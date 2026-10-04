package com.kernel.ai

internal data class Pr1451ForegroundWindowEvidence(
    val packageName: String?,
    val type: Int?,
    val focused: Boolean,
    val active: Boolean,
)

internal data class Pr1451ForegroundWindowSnapshot(
    val windows: List<Pr1451ForegroundWindowEvidence>,
    val readError: String? = null,
)

internal data class Pr1451BackgroundWaitResult(
    val homeAccessibilityEventObserved: Boolean,
    val beforeHome: Pr1451ForegroundWindowSnapshot,
    val lastSnapshot: Pr1451ForegroundWindowSnapshot,
    val elapsedMs: Long,
    val backgroundWindow: Pr1451ForegroundWindowEvidence?,
) {
    fun failureMessage(targetPackageName: String, timeoutMs: Long): String =
        "Could not observe a foreground application window outside $targetPackageName within " +
            "${timeoutMs}ms: home_accessibility_event_observed=$homeAccessibilityEventObserved, " +
            "elapsed_ms=$elapsedMs, before_home=$beforeHome, last_snapshot=$lastSnapshot"
}

/**
 * Treats the HOME key result only as diagnostics. Success requires a fresh, known, focused or
 * active application window belonging to a different package than the benchmark app.
 */
internal fun awaitPr1451Background(
    targetPackageName: String,
    applicationWindowType: Int,
    timeoutMs: Long,
    pollIntervalMs: Long,
    pressHome: () -> Boolean,
    snapshotWindows: () -> Pr1451ForegroundWindowSnapshot,
    elapsedRealtimeMs: () -> Long,
    sleep: (Long) -> Unit,
): Pr1451BackgroundWaitResult {
    require(targetPackageName.isNotBlank()) { "Target package must not be blank" }
    require(timeoutMs > 0L) { "Background wait timeout must be positive" }
    require(pollIntervalMs > 0L) { "Background poll interval must be positive" }

    val beforeHome = snapshotWindows()
    val startedAtMs = elapsedRealtimeMs()
    val homeAccessibilityEventObserved = pressHome()
    var lastSnapshot = snapshotWindows()

    while (true) {
        val elapsedMs = (elapsedRealtimeMs() - startedAtMs).coerceAtLeast(0L)
        val backgroundWindow = if (lastSnapshot.readError == null) {
            lastSnapshot.windows.firstOrNull { window ->
                window.type == applicationWindowType &&
                    !window.packageName.isNullOrBlank() &&
                    window.packageName != targetPackageName &&
                    (window.focused || window.active)
            }
        } else {
            null
        }
        if (elapsedMs <= timeoutMs && backgroundWindow != null) {
            return Pr1451BackgroundWaitResult(
                homeAccessibilityEventObserved = homeAccessibilityEventObserved,
                beforeHome = beforeHome,
                lastSnapshot = lastSnapshot,
                elapsedMs = elapsedMs,
                backgroundWindow = backgroundWindow,
            )
        }
        if (elapsedMs >= timeoutMs) {
            return Pr1451BackgroundWaitResult(
                homeAccessibilityEventObserved = homeAccessibilityEventObserved,
                beforeHome = beforeHome,
                lastSnapshot = lastSnapshot,
                elapsedMs = elapsedMs,
                backgroundWindow = null,
            )
        }

        sleep(minOf(pollIntervalMs, timeoutMs - elapsedMs))
        lastSnapshot = snapshotWindows()
    }
}
