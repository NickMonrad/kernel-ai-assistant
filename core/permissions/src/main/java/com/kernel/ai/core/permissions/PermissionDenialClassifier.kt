package com.kernel.ai.core.permissions

/**
 * Outcome of a runtime permission denial classification.
 *
 * [RetryableDenied] — the user denied the permission, but the system will show
 * the prompt again on the next request. Show the normal request overlay.
 *
 * [RepairOnlyDenied] — Android will no longer show the runtime permission prompt.
 * Direct the user to system Settings for manual repair.
 */
enum class DenialOutcome {
    RetryableDenied,
    RepairOnlyDenied,
}

/**
 * Classifies Android runtime permission denials to distinguish retryable denials
 * from repair-only (permanent) denials.
 *
 * Behaviour is based on denial count per permission and the
 * [android.app.Activity.shouldShowRequestPermissionRationale] signal.
 *
 * Rule:
 * - First denial → [RetryableDenied] regardless of `shouldShowRationale`.
 * - Second or later denial with `shouldShowRationale == false` → [RepairOnlyDenied].
 * - Second or later denial with `shouldShowRationale == true` → [RetryableDenied]
 *   (Android will re-show the prompt).
 *
 * Counters are cleared via [clear] (on grant, fallback, or deliberate dismiss) or
 * [clearAll].
 */
class PermissionDenialClassifier {

    private val denialCounts = mutableMapOf<String, Int>()

    /**
     * Classify a permission denial and return the outcome.
     * Increments the internal denial counter for [permission].
     *
     * @param permission The Android runtime permission string (e.g.
     *   [android.Manifest.permission.CALL_PHONE]).
     * @param shouldShowRationale The value of
     *   [androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale]
     *   at the time of the denial callback.
     */
    fun classify(
        permission: String,
        shouldShowRationale: Boolean,
    ): DenialOutcome {
        val count = denialCounts.getOrDefault(permission, 0) + 1
        denialCounts[permission] = count

        return when {
            count == 1 -> DenialOutcome.RetryableDenied
            !shouldShowRationale -> DenialOutcome.RepairOnlyDenied
            else -> DenialOutcome.RetryableDenied
        }
    }

    /**
     * Clear the denial counter for a single [permission].
     * Call on grant, fallback (e.g. dialer, type place), or deliberate dismiss.
     */
    fun clear(permission: String) {
        denialCounts.remove(permission)
    }

    /** Clear all denial counters. */
    fun clearAll() {
        denialCounts.clear()
    }
}
