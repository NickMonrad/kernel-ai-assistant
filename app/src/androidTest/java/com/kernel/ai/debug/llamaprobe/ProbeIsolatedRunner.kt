package com.kernel.ai.debug.llamaprobe

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Skips onCreate only for the dedicated test-only llamaProbe Application. */
class ProbeIsolatedRunner : AndroidJUnitRunner() {
    private var probeOnly = false

    override fun onCreate(arguments: Bundle) {
        probeOnly = arguments.getString(ARG_PROBE_ONLY) == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (probeOnly) {
            check(app.javaClass.name == PROBE_APPLICATION_CLASS) {
                "probeOnly=true requires the isolated llamaProbe Application"
            }
        } else {
            super.callApplicationOnCreate(app)
        }
    }

    companion object {
        const val ARG_PROBE_ONLY = "probeOnly"
        private const val PROBE_APPLICATION_CLASS = "com.kernel.ai.debug.llamaprobe.ProbeApplication"
    }
}
