package com.kernel.ai.core.voice

/**
 * Metadata for a single Semaine multi-speaker voice.
 *
 * [sid] maps directly to the `sid` parameter in Sherpa-ONNX's `generate(text, sid, speed)`.
 * [displayName] is the human-readable speaker name (used in the Settings → Voice picker).
 * [description] is a short description of the speaker's character.
 */
data class SemaineSpeaker(
    val sid: Int,
    val displayName: String,
    val description: String,
) {
    val displayLabel: String get() = "$displayName — $description"
}

/**
 * Bundled metadata for all 4 speakers in the `en_GB-semaine-medium` Piper model.
 *
 * Sid mapping sourced from the en_GB-semaine-medium.onnx.json speaker_id_map:
 *   0 = prudence, 1 = spike, 2 = obadiah, 3 = poppy
 */
object SemaineSpeakerMetadata {

    val speakers: List<SemaineSpeaker> = listOf(
        SemaineSpeaker(sid = 0, displayName = "Prudence", description = "female, calm"),
        SemaineSpeaker(sid = 1, displayName = "Spike",    description = "male, neutral"),
        SemaineSpeaker(sid = 2, displayName = "Obadiah",  description = "male, melancholic"),
        SemaineSpeaker(sid = 3, displayName = "Poppy",    description = "female, upbeat"),
    )

    /**
     * Returns the display label for the given [sid], falling back to "Prudence" (sid=0)
     * if the sid does not match any exposed speaker.
     */
    fun displayLabel(sid: Int): String =
        speakers.firstOrNull { it.sid == sid }?.displayLabel ?: speakers.first().displayLabel
}
