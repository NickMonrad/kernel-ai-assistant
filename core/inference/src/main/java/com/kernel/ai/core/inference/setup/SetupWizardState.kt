package com.kernel.ai.core.inference.setup

/**
 * Ordered steps of the first-run setup wizard (#1855). Part 2 adds speech input and output (VOICE) before
 * the download step, so the download size reflects the chosen voice. Part 3 adds MAKE_JANDAL_YOURS after the
 * download: optional Hey Jandal, default assistant, notifications and exact alarms. Each step is persisted by
 * [name], so keep names stable.
 */
enum class SetupStep {
    HOW_IT_WORKS,
    VOICE,
    DOWNLOAD,
    MAKE_JANDAL_YOURS,
    ;

    companion object {
        /** Restores a persisted step. Unknown or missing names fall back to the first step. */
        fun fromStorageName(name: String?): SetupStep = entries.firstOrNull { it.name == name } ?: HOW_IT_WORKS
    }
}

/** Pure navigation over [SetupStep]. The wizard screen calls these; it holds no step logic of its own. */
object SetupWizardFlow {
    /** The step after [step], or null when [step] is the last one (finishing the wizard). */
    fun next(step: SetupStep): SetupStep? = SetupStep.entries.getOrNull(step.ordinal + 1)

    /** The step before [step], or null on the first step (back leaves the wizard). */
    fun previous(step: SetupStep): SetupStep? = SetupStep.entries.getOrNull(step.ordinal - 1)

    /**
     * Whether the wizard should be completed at first launch of a build that has it. A new install
     * stays incomplete (shown once). An updated install is marked complete, so it is not shown the wizard.
     * Once [alreadyDecided], the stored value stands.
     */
    fun completedAfterFirstLaunchDecision(
        alreadyDecided: Boolean,
        previouslyCompleted: Boolean,
        existingInstall: Boolean,
    ): Boolean = previouslyCompleted || (!alreadyDecided && existingInstall)
}
