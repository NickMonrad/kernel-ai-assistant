package com.kernel.ai.core.inference.download

import android.content.Context

/**
 * True when this app was updated from an earlier install (last update later than first install).
 * Fresh installs are false. Used to keep updated installs on their current behaviour and out of the
 * first-run wizard (#1855). Decided from the package install record, never from queued work.
 */
fun Context.isUpdatedInstall(): Boolean = runCatching {
    val info = packageManager.getPackageInfo(packageName, 0)
    info.lastUpdateTime > info.firstInstallTime
}.getOrDefault(false)
