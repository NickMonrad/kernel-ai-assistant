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
        // Determine backend based on tier, but skip GPU on known-bad SoC/GPU combos.
        // Exynos chips (Mali GPU) have unstable OpenCL drivers that hang during
        // Engine.initialize() — observed on Exynos 2100 (S21) #684, NPU CDSP #609.
        val skipGpuForSoc = false // GPU works on Exynos 2100 with timeout guard — see #684 testing
        if (skipGpuForSoc) {
            Log.w(TAG, "SoC $socManufacturer $socModel with Mali GPU on tier=${tier.name} " +
                "— forcing CPU backend to avoid GPU driver hangs (#684)")
        }

        val recommendedBackend = when {
            tier == HardwareTier.FLAGSHIP && !skipGpuForSoc -> BackendType.GPU
            tier == HardwareTier.MID_RANGE && !skipGpuForSoc -> BackendType.GPU
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

    private fun shouldSkipGpuForSoc(
        socManufacturer: String,
        socModel: String,
        tier: HardwareTier,
    ): Boolean = isMaliGpuSoc(socManufacturer, socModel, tier)
}

/**
 * Returns true when the SoC has a Mali GPU (Exynos, MediaTek, HiSilicon) and the
 * device tier is MID_RANGE or below. Mali OpenCL drivers are known to hang during
 * `Engine.initialize()` on LiteRT — forcing CPU avoids the hang and provides
 * a working (albeit slower) fallback (#684).
 *
 * FLAGSHIP devices (≥10 GB) with Exynos are NOT blacklisted — the S24 Ultra
 * (Exynos 2400, Xclipse GPU based on AMD RDNA 3) has a stable OpenCL driver.
 * Only MID_RANGE and LOW_POWER Mali devices are affected.
 */
internal fun isMaliGpuSoc(
    socManufacturer: String,
    socModel: String,
    tier: HardwareTier,
): Boolean {
    // Only apply to MID_RANGE and below — FLAGSHIP devices have enough
    // headroom that GPU init doesn't fail the same way.
    if (tier == HardwareTier.FLAGSHIP) return false

    val mfr = socManufacturer.uppercase()
    val model = socModel.uppercase()

    // Samsung Exynos SoCs use Mali GPUs (Exynos 2100, 2200, etc.)
    // Model strings: "exynos2100", "S5E9845", "Exynos 2100"
    if (mfr.contains("SAMSUNG") && (model.contains("EXYNOS") || model.contains("S5E"))) {
        return true
    }

    // MediaTek Dimensity SoCs use Mali GPUs
    if (mfr.contains("MEDIATEK")) {
        return true
    }

    // HiSilicon Kirin SoCs use Mali GPUs
    if (mfr.contains("HISILICON")) {
        return true
    }

    return false
}
