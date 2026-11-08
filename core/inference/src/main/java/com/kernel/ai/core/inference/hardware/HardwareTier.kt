package com.kernel.ai.core.inference.hardware

/**
 * Device capability tier used to select appropriate model sizes and backends.
 *
 * Thresholds are based on total RAM:
 * - [FLAGSHIP] ≥10 GB  → Full RAG + E-4B + GPU (OpenCL / Adreno 740), maxTokens=8000
 * - [MID_RANGE] ≥6 GB  → E-2B + GPU, maxTokens=2000
 * - [LOW_POWER] <6 GB  → Intent routing only + CPU, maxTokens=1000
 */
enum class HardwareTier {
    FLAGSHIP,
    MID_RANGE,
    LOW_POWER;

    companion object {
        fun fromRamBytes(totalRamBytes: Long): HardwareTier = when {
            totalRamBytes >= 10L * 1024 * 1024 * 1024 -> FLAGSHIP
            totalRamBytes >= 6L * 1024 * 1024 * 1024 -> MID_RANGE
            else -> LOW_POWER
        }
    }
}
