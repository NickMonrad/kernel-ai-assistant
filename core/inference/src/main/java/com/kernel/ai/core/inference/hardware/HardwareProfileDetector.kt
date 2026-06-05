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

        // Determine backend based on tier, but skip GPU on known-bad SoC/GPU combos.
        // Exynos 2100 (S21) is allowlisted — GPU init completes ~24s with 60s timeout guard (#684).
        val skipGpuForSoc = shouldSkipGpuForSoc(socManufacturer, socModel, tier)

        val recommendedBackend = when {
            tier == HardwareTier.FLAGSHIP && !skipGpuForSoc -> BackendType.GPU
            tier == HardwareTier.MID_RANGE && !skipGpuForSoc -> BackendType.GPU
            else -> BackendType.CPU
        }

        val recommendedMaxTokens = when (tier) {
            HardwareTier.FLAGSHIP -> 8000
            HardwareTier.MID_RANGE -> 3072
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
    ): Boolean {
        // Allowlist: specific SoCs verified to work with GPU via on-device testing.
        if (isGpuAllowlisted(socManufacturer, socModel)) {
            Log.d(TAG, "SoC $socManufacturer $socModel is GPU-allowlisted — using GPU backend")
            return false
        }
        // Otherwise, skip GPU on Mali GPU SoCs at MID_RANGE and below.
        val skip = isMaliGpuSoc(socManufacturer, socModel, tier)
        if (skip) {
            Log.w(TAG, "SoC $socManufacturer $socModel with Mali GPU on tier=${tier.name} " +
                "— forcing CPU backend to avoid GPU driver hangs (#684)")
        }
        return skip
    }
}

/**
 * Returns true when the SoC is verified to work with GPU via on-device testing.
 * These devices bypass the Mali GPU blacklist in [HardwareProfileDetector].
 *
 * Current allowlist:
 * - Exynos 2100 / S5E9845 (Samsung S21, SM-G991B): GPU init ~24s, stable with 60s timeout (#684)
 */
internal fun isGpuAllowlisted(
    socManufacturer: String,
    socModel: String,
): Boolean {
    val mfr = socManufacturer.uppercase()
    val model = socModel.uppercase().replace(" ", "")

    // Exynos 2100 (S21) — confirmed working with GPU, see #684 on-device test results.
    if (mfr.contains("SAMSUNG") && (model.contains("EXYNOS2100") || model.contains("S5E9845"))) {
        return true
    }

    return false
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

    // Samsung Exynos SoCs mostly use Mali GPUs, but Exynos 2200+ use AMD Xclipse
    // (RDNA 2/3) which has a stable OpenCL driver — do NOT blacklist these.
    // Model strings: "exynos2100", "S5E9845", "Exynos 2100"
    if (mfr.contains("SAMSUNG") && (model.contains("EXYNOS") || model.contains("S5E"))) {
        // Exynos 2200 (S5E9925) and Exynos 2400 (S5E9945) = Xclipse, not Mali
        if (model.contains("2200") || model.contains("2400") ||
            model.contains("S5E9925") || model.contains("S5E9945")) {
            return false
        }
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