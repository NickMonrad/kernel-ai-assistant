package com.kernel.ai.core.voice

/**
 * Per-detector-run counters for low-frequency wake-word diagnostics.
 *
 * The detector updates primitive fields only; callers create a [WakeWordDiagnosticSnapshot]
 * only when emitting a periodic or shutdown summary. This keeps the AudioRecord hot loop
 * allocation-free while allowing controlled battery tests to derive inference cadence and
 * silence-gate effectiveness.
 */
internal class WakeWordDiagnosticCounters {
    var audioFrames: Long = 0
        private set
    var stage1Executions: Long = 0
        private set
    var stage2Executions: Long = 0
        private set
    var stage3Executions: Long = 0
        private set
    var silenceGateSkips: Long = 0
        private set
    var verifierInvocations: Long = 0
        private set
    var verifierPasses: Long = 0
        private set
    var verifierRejects: Long = 0
        private set
    var highConfidenceActivations: Long = 0
        private set
    var verifiedActivations: Long = 0
        private set

    fun recordAudioFrame() { audioFrames++ }
    fun recordStage1Execution() { stage1Executions++ }
    fun recordStage2Execution() { stage2Executions++ }
    fun recordStage3Execution() { stage3Executions++ }
    fun recordSilenceGateSkip() { silenceGateSkips++ }
    fun recordVerifierResult(passed: Boolean) {
        verifierInvocations++
        if (passed) verifierPasses++ else verifierRejects++
    }
    fun recordHighConfidenceActivation() { highConfidenceActivations++ }
    fun recordVerifiedActivation() { verifiedActivations++ }

    fun snapshot(elapsedMillis: Long): WakeWordDiagnosticSnapshot = WakeWordDiagnosticSnapshot(
        elapsedMillis = elapsedMillis.coerceAtLeast(0L),
        audioFrames = audioFrames,
        stage1Executions = stage1Executions,
        stage2Executions = stage2Executions,
        stage3Executions = stage3Executions,
        silenceGateSkips = silenceGateSkips,
        verifierInvocations = verifierInvocations,
        verifierPasses = verifierPasses,
        verifierRejects = verifierRejects,
        highConfidenceActivations = highConfidenceActivations,
        verifiedActivations = verifiedActivations,
    )
}

internal data class WakeWordDiagnosticSnapshot(
    val elapsedMillis: Long,
    val audioFrames: Long,
    val stage1Executions: Long,
    val stage2Executions: Long,
    val stage3Executions: Long,
    val silenceGateSkips: Long,
    val verifierInvocations: Long,
    val verifierPasses: Long,
    val verifierRejects: Long,
    val highConfidenceActivations: Long,
    val verifiedActivations: Long,
) {
    val stage23EligibleFrames: Long
        get() = stage2Executions + silenceGateSkips

    val silenceGateSkipRatio: Double
        get() = stage23EligibleFrames.takeIf { it > 0L }
            ?.let { silenceGateSkips.toDouble() / it }
            ?: 0.0

    fun stage2ExecutionsPerHour(): Double = executionsPerHour(stage2Executions)

    fun stage3ExecutionsPerHour(): Double = executionsPerHour(stage3Executions)

    fun verifierInvocationsPerHour(): Double = executionsPerHour(verifierInvocations)

    private fun executionsPerHour(executions: Long): Double {
        if (elapsedMillis <= 0L) return 0.0
        return executions.toDouble() * 3_600_000.0 / elapsedMillis
    }
}
