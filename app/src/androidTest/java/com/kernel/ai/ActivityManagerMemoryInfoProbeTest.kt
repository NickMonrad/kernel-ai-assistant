package com.kernel.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only system-memory preflight for the PR #1625 host acceptance procedure.
 *
 * Invoke only the debug test APK directly:
 * `adb shell am instrument -w -r -e class com.kernel.ai.ActivityManagerMemoryInfoProbeTest#reportsActivityManagerMemoryInfo com.kernel.ai.debug.test/androidx.test.runner.AndroidJUnitRunner`
 *
 * Parse the `AM_MEMORY_INFO_JSON=` logcat line under [TAG]. This probe does not start the app UI,
 * initialize inference, or access model, database, permission, or user state.
 */
class ActivityManagerMemoryInfoProbeTest {
    @Test
    fun reportsActivityManagerMemoryInfo() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext
        assertTrue(
            "This read-only preflight probe is restricted to the debug application",
            context.packageName == DEBUG_PACKAGE,
        )

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw AssertionError("ActivityManager service is unavailable")
        val info = ActivityManager.MemoryInfo()
        try {
            manager.getMemoryInfo(info)
        } catch (failure: Exception) {
            throw AssertionError("ActivityManager.getMemoryInfo() failed", failure)
        }

        assertTrue("ActivityManager returned invalid availMem=${info.availMem}", info.availMem > 0L)
        assertTrue("ActivityManager returned invalid threshold=${info.threshold}", info.threshold > 0L)
        assertTrue("ActivityManager returned invalid totalMem=${info.totalMem}", info.totalMem > 0L)

        val result = JSONObject()
            .put("schema", "android.activity_manager_memory_info.v1")
            .put("lowMemory", info.lowMemory)
            .put("availMemBytes", info.availMem)
            .put("thresholdBytes", info.threshold)
            .put("totalMemBytes", info.totalMem)
        Log.i(TAG, "AM_MEMORY_INFO_JSON=$result")
    }

    private companion object {
        const val DEBUG_PACKAGE = "com.kernel.ai.debug"
        const val TAG = "AmMemProbe"
    }
}
