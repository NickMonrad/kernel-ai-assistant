package com.kernel.ai.debug.llamaprobe

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Skips the Jandal Application startup only for the explicitly requested native probe. */
class ProbeIsolatedRunner : AndroidJUnitRunner() {
    private var probeOnly = false

    override fun onCreate(arguments: Bundle) {
        probeOnly = arguments.getString(ARG_PROBE_ONLY) == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (!probeOnly) super.callApplicationOnCreate(app)
    }

    companion object {
        const val ARG_PROBE_ONLY = "probeOnly"
    }
}
