package com.kernel.ai.core.inference.hardware

import com.kernel.ai.core.inference.BackendType

/**
 * Immutable snapshot of detected device capabilities.
 *
 * @param tier              The capability tier, derived from [totalRamBytes].
 * @param totalRamBytes     Physical RAM reported by [android.app.ActivityManager].
 * @param socManufacturer   SoC vendor (e.g. "Qualcomm") from [android.os.Build.SOC_MANUFACTURER].
 * @param socModel          SoC model string (e.g. "SM8550") from [android.os.Build.SOC_MODEL].
 * @param hasQualcommNpu    True when a Qualcomm Hexagon NPU is likely present.
 * @param recommendedBackend  The best backend for this device without trial-and-error.
 * @param recommendedMaxTokens  Safe KV-cache window for this tier.
 */
data class HardwareProfile(
    val tier: HardwareTier,
    val totalRamBytes: Long,
    val socManufacturer: String,
    val socModel: String,
    val hasQualcommNpu: Boolean,
    val recommendedBackend: BackendType,
    val recommendedMaxTokens: Int,
) {
    /** Human-readable RAM string, e.g. "12 GB". */
    val ramLabel: String get() {
        val gb = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        return "%.0f GB".format(gb)
    }
}
