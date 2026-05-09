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
        "Sets the emotional tone of the spoken voice for the CURRENT reply. " +
            "Call this SILENTLY before composing your response — never describe that you are setting the tone. " +
            "Trigger: (a) user explicitly asks for a voice tone/emotion (e.g. 'say that in a sad voice', " +
            "'use a happy tone', 'sound angry'); (b) context strongly warrants it (delivering sad news → sad, " +
            "celebrating → happy). One of: neutral, happy, sad, angry, worried."
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

CRITICAL: Call this tool SILENTLY as the FIRST action — never say "I'm setting the voice" or "I've set the voice".
Just call the tool, then compose your response. The tone change happens automatically.

When to call:
  • User asks for a specific tone:
      "tell me a joke in a sad voice"              → set_voice_emotion(emotion="sad"), then tell a joke
      "say that in a happy voice"                  → set_voice_emotion(emotion="happy"), then answer
      "sound worried" / "use an angry tone"        → set_voice_emotion(emotion=matching_emotion)
      "set your voice to sad"                      → set_voice_emotion(emotion="sad"), confirm done
  • Context strongly warrants it:
      Delivering genuinely bad news → sad
      Celebrating something → happy
      Warning about a serious risk → worried

Do NOT say "I don't have a tool" — this tool is available. Do NOT narrate calling it.
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
