package com.kernel.ai.debug.acoustic

import android.content.Intent
import android.os.Bundle
import java.util.Locale

internal object AcousticStimulusContract {
    const val ACTION_PLAY = "com.kernel.ai.debug.action.PLAY_ACOUSTIC_STIMULUS"
    const val EXTRA_TRIAL_ID = "trial_id"
    const val EXTRA_FIXTURE_ID = "fixture_id"
    const val EXTRA_VOLUME_INDEX = "volume_index"
    const val EXTRA_PLAYER_GAIN = "player_gain"
    const val EXTRA_FIXTURE_PATH = "fixture_path"

    const val RESULT_OK = 0
    const val RESULT_REJECTED = 1
    const val RESULT_FAILED = 2

    const val MAX_TRIAL_ID_LENGTH = 128
    const val MAX_FIXTURE_ID_LENGTH = 64
    const val MIN_PLAYER_GAIN = 0.1f
    const val MAX_PLAYER_GAIN = 1.0f
    const val HARD_TIMEOUT_MS = 7_000L

    val acceptedExtras: Set<String> = setOf(
        EXTRA_TRIAL_ID,
        EXTRA_FIXTURE_ID,
        EXTRA_VOLUME_INDEX,
        EXTRA_PLAYER_GAIN,
    )
}

data class StimulusInvocation(
    val trialId: String,
    val fixtureId: String,
    val volumeIndex: Int,
    val playerGain: Float,
)

data class InvalidInvocation(
    val category: String,
    val trialId: String?,
    val fixtureId: String?,
)

sealed interface InvocationParseResult {
    data class Valid(val invocation: StimulusInvocation) : InvocationParseResult
    data class Invalid(val error: InvalidInvocation) : InvocationParseResult
}

internal object InvocationParser {
    private val trialIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val fixtureIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    fun parse(intent: Intent): InvocationParseResult {
        val extras = intent.extras
        val values = extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap()
        return parse(intent.action, values)
    }

    fun parse(action: String?, values: Map<String, Any?>): InvocationParseResult {
        if (action != AcousticStimulusContract.ACTION_PLAY) {
            return invalid("invalid_action", values[AcousticStimulusContract.EXTRA_TRIAL_ID] as? String)
        }
        val unsupportedKey = values.keys.firstOrNull { it !in AcousticStimulusContract.acceptedExtras }
        if (unsupportedKey != null) {
            val category = if (unsupportedKey == AcousticStimulusContract.EXTRA_FIXTURE_PATH) {
                "arbitrary_path_not_allowed"
            } else {
                "unsupported_extra"
            }
            return invalid(category, values[AcousticStimulusContract.EXTRA_TRIAL_ID] as? String)
        }
        if (!values.containsKey(AcousticStimulusContract.EXTRA_TRIAL_ID)) {
            return invalid("missing_trial_id", null)
        }
        val trialId = values[AcousticStimulusContract.EXTRA_TRIAL_ID] as? String
        if (trialId == null || trialId.length !in 1..AcousticStimulusContract.MAX_TRIAL_ID_LENGTH ||
            !trialIdPattern.matches(trialId)
        ) {
            return invalid("malformed_trial_id", trialId)
        }
        if (!values.containsKey(AcousticStimulusContract.EXTRA_FIXTURE_ID)) {
            return invalid("missing_fixture_id", trialId)
        }
        val fixtureId = values[AcousticStimulusContract.EXTRA_FIXTURE_ID] as? String
        if (fixtureId == null || fixtureId.isBlank()) {
            return invalid("missing_fixture_id", trialId)
        }
        if (fixtureId.contains('/') || fixtureId.contains('\\') || fixtureId.contains("..")) {
            return invalid("arbitrary_path_not_allowed", trialId, fixtureId)
        }
        if (fixtureId.length !in 1..AcousticStimulusContract.MAX_FIXTURE_ID_LENGTH ||
            !fixtureIdPattern.matches(fixtureId)
        ) {
            return invalid("malformed_fixture_id", trialId, fixtureId)
        }
        val rawVolume = values[AcousticStimulusContract.EXTRA_VOLUME_INDEX]
        if (rawVolume !is Int) {
            return invalid("missing_or_malformed_volume_index", trialId, fixtureId)
        }
        if (rawVolume < 1) {
            return invalid("unsafe_volume_index", trialId, fixtureId)
        }
        val gain = when {
            !values.containsKey(AcousticStimulusContract.EXTRA_PLAYER_GAIN) ->
                AcousticStimulusContract.MAX_PLAYER_GAIN
            values[AcousticStimulusContract.EXTRA_PLAYER_GAIN] !is Number ->
                return invalid("malformed_player_gain", trialId, fixtureId)
            else -> (values[AcousticStimulusContract.EXTRA_PLAYER_GAIN] as Number).toFloat()
        }
        if (!gain.isFinite() || gain !in AcousticStimulusContract.MIN_PLAYER_GAIN..AcousticStimulusContract.MAX_PLAYER_GAIN) {
            return invalid("unsafe_player_gain", trialId, fixtureId)
        }
        return InvocationParseResult.Valid(
            StimulusInvocation(trialId, fixtureId, rawVolume, gain),
        )
    }

    private fun invalid(category: String, trialId: String?, fixtureId: String? = null) =
        InvocationParseResult.Invalid(InvalidInvocation(category, trialId, fixtureId))

    private fun Bundle.stringValue(key: String): String? = get(key) as? String
}

internal fun String.sha256Normalised(): String = lowercase(Locale.US)
