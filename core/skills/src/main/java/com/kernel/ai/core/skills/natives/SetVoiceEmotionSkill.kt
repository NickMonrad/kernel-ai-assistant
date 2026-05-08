package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Singleton
class SetVoiceEmotionSkill @Inject constructor(
    private val voiceController: SherpaOnnxVoiceOutputController,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : Skill {
    override val name = "set_voice_emotion"
    override val description =
        "Sets the emotional tone of the spoken voice for the current reply. " +
            "Only effective when the Semaine voice is selected. " +
            "Use sparingly — match the user's emotional context. " +
            "Do NOT use with other voices."
    override val schema = SkillSchema(
        parameters = mapOf(
            "emotion" to SkillParameter(
                type = "string",
                description = "The emotional style for the next spoken response. " +
                    "One of: neutral, happy, sad, angry, worried.",
            ),
        ),
        required = listOf("emotion"),
    )
    override val fullInstructions = """
set_voice_emotion: Set the emotional tone of the spoken voice for the current reply.

Parameters:
- emotion (required, string): One of "neutral", "happy", "sad", "angry", "worried"

Emotion → Semaine speaker mapping:
  neutral → calm, measured delivery (default)
  happy   → upbeat, positive tone
  sad     → subdued, gentle tone
  angry   → intense, forceful delivery
  worried → concerned, careful delivery

Use sparingly and only when the user's emotional context clearly warrants it.
For example: happy when delivering good news, angry when venting, worried when the user is stressed.
ONLY effective when Semaine voice is selected — has no effect with other voices.
    """.trimIndent()

    // sid mapping matches en_GB-semaine-medium speaker_id_map exactly
    // neutral=0, happy=1, sad=2, angry=3, worried=4
    private val emotionToSid = mapOf("neutral" to 0, "happy" to 1, "sad" to 2, "angry" to 3, "worried" to 4)

    /**
     * Only include this skill in the system prompt when Semaine is the active voice.
     *
     * [runBlocking] is used here because [isEnabled] is called from the skill declaration
     * builder (not a suspend context). This is acceptable for a lightweight DataStore read
     * since declarations are built once per prompt, not on every token.
     */
    override fun isEnabled(): Boolean = runBlocking {
        voiceOutputPreferences.selectedSherpaVoice.first() == SherpaPiperVoice.SemaineMedium
    }

    override suspend fun execute(call: SkillCall): SkillResult {
        val emotion = call.arguments["emotion"]?.lowercase()
            ?: return SkillResult.Failure(name, "Missing 'emotion' argument")
        val sid = emotionToSid[emotion]
            ?: return SkillResult.Failure(
                name,
                "Unknown emotion '$emotion'. Use: neutral, happy, sad, angry, worried",
            )
        voiceController.setEmotionOverrideSid(sid)
        return SkillResult.Success("Voice emotion set to $emotion.")
    }
}
