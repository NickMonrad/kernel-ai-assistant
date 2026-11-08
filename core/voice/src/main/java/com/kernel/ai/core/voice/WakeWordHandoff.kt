package com.kernel.ai.core.voice

/**
 * In-process handoff channel between [WakeWordService] (`:app`) and
 * [VoiceCommandActivity] (`:feature:widget`).
 *
 * **Security:** [VoiceCommandActivity] is `exported=true` (required for Android assistant
 * eligibility). To prevent external apps from injecting arbitrary transcripts via
 * [EXTRA_PREFILLED_TRANSCRIPT], [WakeWordService] sets [pendingTranscript] in JVM memory
 * immediately before calling `startActivity`. [VoiceCommandActivity] reads and clears it,
 * and only trusts the intent extra when the in-process value matches. External callers
 * cannot write this field.
 */
object WakeWordHandoff {
    @Volatile
    var pendingTranscript: String? = null
}
