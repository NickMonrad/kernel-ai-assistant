package com.kernel.ai.core.inference.diagnostics

import com.kernel.ai.core.inference.BuildConfig

/**
 * Gate for log lines that carry user content (calendar titles, locations, keywords, tool params,
 * replies). Off by default. Active only when [BuildConfig.DEBUG] is true AND [enabled] was set by
 * debug-only code, so release builds never emit content (the `DEBUG` branch is a compile-time
 * constant that R8 strips).
 *
 * Nothing in the app sets [enabled] yet; there is no user-facing toggle. Add a debug-only setter
 * before relying on it for diagnosis.
 */
object DebugVerboseLog {
    @Volatile
    var enabled: Boolean = false

    /** True only in debug builds with the verbose flag explicitly turned on. */
    fun active(): Boolean = BuildConfig.DEBUG && enabled
}
