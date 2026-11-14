package com.kernel.ai.core.inference.diagnostics

import com.kernel.ai.core.inference.BuildConfig

/**
 * Gate for log lines that carry user content (calendar titles, locations, keywords, tool params,
 * replies). Off by default. Active only when [BuildConfig.DEBUG] is true AND [enabled] was set by
 * debug-only code, so release builds never emit content (the `DEBUG` branch is a compile-time
 * constant that R8 strips).
 *
 * Set by `MainActivity` on every launch: true only when the launch carries the adb harness token
 * (debug builds only). Any other launch turns it off. There is no user-facing toggle.
 */
object DebugVerboseLog {
    @Volatile
    var enabled: Boolean = false

    /** True only in debug builds with the verbose flag explicitly turned on. */
    fun active(): Boolean = BuildConfig.DEBUG && enabled
}
