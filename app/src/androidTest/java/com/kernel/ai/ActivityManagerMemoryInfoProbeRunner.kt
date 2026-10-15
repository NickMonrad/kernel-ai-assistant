package com.kernel.ai

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Suppresses Application.onCreate only when the memory probe class is selected.
 * `newApplication` precedes runner `onCreate` on supported APIs, so suppression belongs at
 * `callApplicationOnCreate`, after the runner has received its arguments.
 */
class ActivityManagerMemoryInfoProbeRunner : AndroidJUnitRunner() {
    private var suppressApplicationOnCreate = false

    internal var didSuppressApplicationOnCreate = false
        private set

    override fun onCreate(arguments: Bundle?) {
        suppressApplicationOnCreate =
            arguments?.getString(CLASS_ARGUMENT)?.substringBefore('#') == MEMORY_PROBE_CLASS
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (suppressApplicationOnCreate) {
            didSuppressApplicationOnCreate = true
            return
        }
        super.callApplicationOnCreate(app)
    }

    private companion object {
        const val CLASS_ARGUMENT = "class"
        const val MEMORY_PROBE_CLASS = "com.kernel.ai.ActivityManagerMemoryInfoProbeTest"
    }
}
