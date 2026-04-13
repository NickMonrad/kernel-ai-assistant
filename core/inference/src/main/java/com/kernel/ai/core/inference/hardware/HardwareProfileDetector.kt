package com.kernel.ai.core.inference.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.kernel.ai.core.inference.BackendType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HardwareProfileDetector"

/**
 * Detects hardware capabilities at startup and returns an immutable [HardwareProfile].
 *
 * Detection logic:
 * 1. Query total physical RAM via [ActivityManager.MemoryInfo].
 * 2. Read [Build.SOC_MANUFACTURER] / [Build.SOC_MODEL] (API 31+).
 * 3. Identify Qualcomm Hexagon NPU by manufacturer string.
 * 4. Map RAM + NPU presence to a [HardwareTier] and recommended [BackendType].
 *
 * The profile is computed once and cached — calling [detect] is idempotent.
 */
@Singleton
class HardwareProfileDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _profile: HardwareProfile by lazy { buildProfile() }

    val profile: HardwareProfile get() = _profile

    private fun buildProfile(): HardwareProfile {
        val totalRam = getTotalRamBytes()
        val socManufacturer = Build.SOC_MANUFACTURER.orEmpty()
        val socModel = Build.SOC_MODEL.orEmpty()

        val hasQualcommNpu = socManufacturer.equals("Qualcomm", ignoreCase = true) ||
            socManufacturer.equals("QUALCOMM", ignoreCase = true)

        val tier = HardwareTier.fromRamBytes(totalRam)

        val recommendedBackend = when {
            tier == HardwareTier.FLAGSHIP && hasQualcommNpu -> BackendType.NPU
            tier == HardwareTier.FLAGSHIP -> BackendType.GPU
            tier == HardwareTier.MID_RANGE -> BackendType.GPU
            else -> BackendType.CPU
        }

        val recommendedMaxTokens = when (tier) {
            HardwareTier.FLAGSHIP -> 8000
            HardwareTier.MID_RANGE -> 2000
            HardwareTier.LOW_POWER -> 1000
        }

        val profile = HardwareProfile(
            tier = tier,
            totalRamBytes = totalRam,
            socManufacturer = socManufacturer,
            socModel = socModel,
            hasQualcommNpu = hasQualcommNpu,
            recommendedBackend = recommendedBackend,
            recommendedMaxTokens = recommendedMaxTokens,
        )

        Log.i(
            TAG,
            "Hardware profile: tier=${tier.name}, ram=${profile.ramLabel}, " +
                "soc=$socManufacturer $socModel, npu=$hasQualcommNpu, " +
                "backend=$recommendedBackend, maxTokens=$recommendedMaxTokens",
        )

        return profile
    }

    private fun getTotalRamBytes(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem
    }
}
