package com.kernel.ai.feature.chat.model

import com.kernel.ai.core.skills.ToolPresentationJson
import org.json.JSONObject

fun ToolCallInfo.toJsonString(): String = JSONObject().apply {
    put("skillName", skillName)
    put("requestJson", requestJson)
    put("resultText", resultText)
    put("isSuccess", isSuccess)
    presentation?.let { put("presentation", ToolPresentationJson.toJsonObject(it)) }
}.toString()

fun toolCallInfoFromJson(json: String?): ToolCallInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = JSONObject(json)
        ToolCallInfo(
            skillName = obj.getString("skillName"),
            requestJson = obj.getString("requestJson"),
            resultText = obj.getString("resultText"),
            isSuccess = obj.getBoolean("isSuccess"),
            presentation = obj.optJSONObject("presentation")?.let(ToolPresentationJson::fromJsonObject),
        )
    }.getOrNull()
}
