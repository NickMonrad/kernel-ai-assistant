package com.kernel.ai.core.inference.hardware

/**
 * Device capability tier used to select appropriate model sizes and backends.
 *
 * Thresholds are based on total RAM:
 * - [FLAGSHIP] ≥10 GB  → Full RAG + E-2B + NPU, maxTokens=8192
 * - [MID_RANGE] ≥6 GB  → E-2B + GPU, maxTokens=2048
 * - [LOW_POWER] <6 GB  → Intent routing only + CPU, maxTokens=1024
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
