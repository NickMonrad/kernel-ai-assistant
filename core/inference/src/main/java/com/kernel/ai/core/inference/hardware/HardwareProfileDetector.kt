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
 * 3. Map RAM to a [HardwareTier] and select the recommended [BackendType].
 *
 * Backend selection: FLAGSHIP and MID_RANGE devices use GPU (OpenCL). LOW_POWER uses CPU.
 * NPU is not used as the default — see [LiteRtInferenceEngine] for explicit-NPU handling.
 *
 * The [profile] property is computed once on first access and cached — subsequent reads
 * return the same [HardwareProfile] instance.
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

        val tier = HardwareTier.fromRamBytes(totalRam)

        // E4B is documented to run on GPU (OpenCL / Adreno 740). NPU (Hexagon via FastRPC)
        // is not used as the recommended backend — it requires /dev/cdsp* device nodes that
        // are absent on the SM8550 target and Engine.initialize() hangs with no timeout when
        // CDSP is unreachable. Explicit BackendType.NPU is still honoured via
        // createEngineWithFallback's standard fallback chain (GPU → CPU on failure).
        val recommendedBackend = when {
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
            recommendedBackend = recommendedBackend,
            recommendedMaxTokens = recommendedMaxTokens,
        )

        Log.i(
            TAG,
            "Hardware profile: tier=${tier.name}, ram=${profile.ramLabel}, " +
                "soc=$socManufacturer $socModel, " +
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
