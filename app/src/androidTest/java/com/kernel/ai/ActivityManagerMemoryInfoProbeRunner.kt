package com.kernel.ai

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Uses the framework Application only when the memory preflight is explicitly selected.
 * All other instrumentation invocations retain the target application's normal lifecycle.
 */
class ActivityManagerMemoryInfoProbeRunner : AndroidJUnitRunner() {
    private var useFrameworkApplication = false

    override fun onCreate(arguments: Bundle?) {
        useFrameworkApplication =
            arguments?.getString(CLASS_ARGUMENT)?.substringBefore('#') == MEMORY_PROBE_CLASS
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        val applicationClass = if (useFrameworkApplication) {
            Application::class.java.name
        } else {
            className
        }
        return super.newApplication(cl, applicationClass, context)
    }

    private companion object {
        const val CLASS_ARGUMENT = "class"
        const val MEMORY_PROBE_CLASS = "com.kernel.ai.ActivityManagerMemoryInfoProbeTest"
    }
}
