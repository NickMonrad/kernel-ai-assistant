package com.kernel.ai.feature.widget

import android.content.Context
import android.util.Log
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentationJson
import com.kernel.ai.core.skills.toSpokenSummary
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceSpeakRequest
import javax.inject.Inject

private const val TAG = "KernelAI"

class VoiceCommandHandler @Inject constructor(
    private val quickIntentRouter: QuickIntentRouter,
    private val skillRegistry: SkillRegistry,
    private val voiceOutputController: VoiceOutputController,
    private val quickActionDao: QuickActionDao,
    private val navigator: WidgetNavigator,
) {
    suspend fun handle(transcript: String, context: Context) {
        Log.d(TAG, "VoiceCommandHandler: routing transcript=\"$transcript\"")
        when (val result = quickIntentRouter.route(transcript)) {
            is QuickIntentRouter.RouteResult.RegexMatch -> {
                Log.d(TAG, "VoiceCommandHandler: RegexMatch → ${result.intent.intentName}")
                if (result.intent.intentName == "start_meal_planner") {
                    navigator.navigateToChat(context, transcript)
                } else {
                    executeIntentInPlace(transcript, result.intent.intentName, result.intent.params)
                }
            }
            is QuickIntentRouter.RouteResult.ClassifierMatch -> {
                Log.d(TAG, "VoiceCommandHandler: ClassifierMatch → ${result.intent.intentName} (${result.confidence})")
                if (result.intent.intentName == "start_meal_planner") {
                    navigator.navigateToChat(context, transcript)
                } else {
                    executeIntentInPlace(transcript, result.intent.intentName, result.intent.params)
                }
            }
            is QuickIntentRouter.RouteResult.NeedsSlot -> {
                Log.d(TAG, "VoiceCommandHandler: NeedsSlot → opening ActionsScreen")
                navigator.navigateToActions(context, transcript)
            }
            is QuickIntentRouter.RouteResult.FallThrough -> {
                Log.d(TAG, "VoiceCommandHandler: FallThrough → opening chat")
                navigator.navigateToChat(context, transcript)
            }
        }
    }

    private suspend fun executeIntentInPlace(
        transcript: String,
        intentName: String,
        params: Map<String, String>,
    ) {
        val directSkill = skillRegistry.get(intentName)
        val (skill, callParams) = when {
            directSkill != null -> directSkill to params
            else -> skillRegistry.get("run_intent") to (mapOf("intent_name" to intentName) + params)
        }

        if (skill == null) {
            Log.w(TAG, "VoiceCommandHandler: no skill found for '$intentName'")
            val entity = QuickActionEntity(
                userQuery = transcript,
                skillName = intentName,
                resultText = "Action recognised but not yet implemented.",
                isSuccess = false,
            )
            quickActionDao.insert(entity)
            voiceOutputController.speak(VoiceSpeakRequest("Action not yet implemented."))
            return
        }

        val skillResult = skill.execute(SkillCall(skill.name, callParams))
        val entity = buildEntity(transcript, intentName, skillResult)
        quickActionDao.insert(entity)
        val spokenText = spokenSummaryFrom(skillResult) ?: entity.resultText
        voiceOutputController.speak(VoiceSpeakRequest(spokenText))
    }

    private fun buildEntity(query: String, skillName: String, result: SkillResult): QuickActionEntity = when (result) {
        is SkillResult.DirectReply -> QuickActionEntity(
            userQuery = query, skillName = skillName,
            resultText = result.content, isSuccess = true,
            presentationJson = result.presentation?.let(ToolPresentationJson::toJsonString),
        )
        is SkillResult.Success -> QuickActionEntity(
            userQuery = query, skillName = skillName,
            resultText = result.content, isSuccess = true,
            presentationJson = result.presentation?.let(ToolPresentationJson::toJsonString),
        )
        is SkillResult.Failure -> QuickActionEntity(
            userQuery = query, skillName = skillName,
            resultText = result.error, isSuccess = false,
        )
        is SkillResult.UnknownSkill -> QuickActionEntity(
            userQuery = query, skillName = skillName,
            resultText = "Unknown skill: ${result.skillName}", isSuccess = false,
        )
        is SkillResult.ParseError -> QuickActionEntity(
            userQuery = query, skillName = skillName,
            resultText = "Parse error: ${result.reason}", isSuccess = false,
        )
    }

    private fun spokenSummaryFrom(result: SkillResult): String? = when (result) {
        is SkillResult.DirectReply -> result.spokenSummary ?: result.presentation?.toSpokenSummary()
        is SkillResult.Success -> result.spokenSummary ?: result.presentation?.toSpokenSummary()
        else -> null
    }
}
