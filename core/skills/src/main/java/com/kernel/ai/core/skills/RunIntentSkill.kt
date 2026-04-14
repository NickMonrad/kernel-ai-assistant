package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.NativeIntentHandler
import javax.inject.Inject
import javax.inject.Singleton

private val STR get() = "<|" + "\"" + "|>"

/**
 * Gateway skill that mirrors Google AI Edge Gallery's `run_intent` pattern.
 *
 * All native Android device actions are routed through this single skill so the model only
 * needs to know ONE function name for all device operations. The [NativeIntentHandler] then
 * maps [intent_name] to the concrete Android API or system Intent.
 *
 * Additional params (subject, body, message, hours, minutes, label) are passed alongside
 * intent_name in the tool call and forwarded to the handler as-is.
 */
@Singleton
class RunIntentSkill @Inject constructor(
    private val handler: NativeIntentHandler,
) : Skill {

    override val name = "run_intent"
    override val description =
        "Perform a native Android device action. Use for flashlight control, sending email, " +
            "sending SMS, setting an alarm (supports optional day name for tomorrow/weekday alarms), " +
            "setting a countdown timer, or creating a calendar event."

    override val schema = SkillSchema(
        parameters = mapOf(
            "intent_name" to SkillParameter(
                type = "string",
                description = "The action to perform.",
                enum = listOf(
                    "toggle_flashlight_on",
                    "toggle_flashlight_off",
                    "send_email",
                    "send_sms",
                    "set_alarm",
                    "set_timer",
                    "create_calendar_event",
                ),
            ),
        ),
        required = listOf("intent_name"),
    )

    override val examples: List<String> = listOf(
        "Flashlight on:   <|tool_call>call:run_intent{intent_name:${STR}toggle_flashlight_on${STR}}<tool_call|>",
        "Flashlight off:  <|tool_call>call:run_intent{intent_name:${STR}toggle_flashlight_off${STR}}<tool_call|>",
        "Send email:      <|tool_call>call:run_intent{intent_name:${STR}send_email${STR},subject:${STR}Subject${STR},body:${STR}Body${STR}}<tool_call|>",
        "Send SMS:        <|tool_call>call:run_intent{intent_name:${STR}send_sms${STR},message:${STR}Hello${STR}}<tool_call|>",
        "Set alarm 7:30:        <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},hours:${STR}7${STR},minutes:${STR}30${STR}}<tool_call|>",
        "Set alarm with label:  <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},hours:${STR}7${STR},minutes:${STR}30${STR},label:${STR}Wake Up${STR}}<tool_call|>",
        "Set alarm tomorrow 8am: <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},hours:${STR}8${STR},minutes:${STR}0${STR},day:${STR}tomorrow${STR}}<tool_call|>",
        "Set alarm next monday:  <|tool_call>call:run_intent{intent_name:${STR}set_alarm${STR},hours:${STR}7${STR},minutes:${STR}0${STR},day:${STR}monday${STR}}<tool_call|>",
        "Set timer 3 min: <|tool_call>call:run_intent{intent_name:${STR}set_timer${STR},duration_seconds:${STR}180${STR}}<tool_call|>",
        "Add calendar event: <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Team lunch${STR},date:${STR}2026-04-15${STR},time:${STR}12:30${STR}}<tool_call|>",
        "Add all-day event:  <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Conference${STR},date:${STR}2026-04-20${STR}}<tool_call|>",
        "Add reminder date:  <|tool_call>call:run_intent{intent_name:${STR}create_calendar_event${STR},title:${STR}Call dentist${STR},date:${STR}2026-04-18${STR},time:${STR}09:00${STR},duration_minutes:${STR}30${STR}}<tool_call|>",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val intentName = call.arguments["intent_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: intent_name.")
        val extraParams = call.arguments - "intent_name"
        return handler.handle(intentName, extraParams)
    }
}
