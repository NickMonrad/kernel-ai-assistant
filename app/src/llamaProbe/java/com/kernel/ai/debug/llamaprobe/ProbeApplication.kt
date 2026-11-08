package com.kernel.ai.debug.llamaprobe

import android.app.Application

/** Test-only Application; onCreate only records whether the isolated runner invoked it. */
class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        onCreateObserved = true
    }

    companion object {
        @JvmField
        @Volatile
        var onCreateObserved = false
    }
}
