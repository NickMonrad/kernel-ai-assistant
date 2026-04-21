package com.kernel.ai.core.skills

import org.json.JSONArray
import org.json.JSONObject

sealed interface ToolPresentation {
    data class Weather(
        val locationName: String,
        val temperatureText: String,
        val feelsLikeText: String?,
        val description: String,
        val emoji: String,
        val humidityText: String?,
        val windText: String?,
        val precipText: String?,
        val airQualityText: String?,
    ) : ToolPresentation

    data class Status(
        val icon: String,
        val title: String,
        val subtitle: String? = null,
    ) : ToolPresentation

    data class ListPreview(
        val title: String,
        val items: List<String>,
        val totalCount: Int,
        val emptyMessage: String? = null,
    ) : ToolPresentation

    data class ComputedResult(
        val primaryText: String,
        val contextText: String,
        val breakdownText: String? = null,
    ) : ToolPresentation
}

object ToolPresentationJson {
    private const val KEY_TYPE = "type"

    fun toJsonString(presentation: ToolPresentation): String = toJsonObject(presentation).toString()

    fun fromJsonString(json: String?): ToolPresentation? {
        if (json.isNullOrBlank()) return null
        return runCatching { fromJsonObject(JSONObject(json)) }.getOrNull()
    }

    fun toJsonObject(presentation: ToolPresentation): JSONObject = when (presentation) {
        is ToolPresentation.Weather -> JSONObject().apply {
            put(KEY_TYPE, "weather")
            put("locationName", presentation.locationName)
            put("temperatureText", presentation.temperatureText)
            put("feelsLikeText", presentation.feelsLikeText)
            put("description", presentation.description)
            put("emoji", presentation.emoji)
            put("humidityText", presentation.humidityText)
            put("windText", presentation.windText)
            put("precipText", presentation.precipText)
            put("airQualityText", presentation.airQualityText)
        }

        is ToolPresentation.Status -> JSONObject().apply {
            put(KEY_TYPE, "status")
            put("icon", presentation.icon)
            put("title", presentation.title)
            put("subtitle", presentation.subtitle)
        }

        is ToolPresentation.ListPreview -> JSONObject().apply {
            put(KEY_TYPE, "list_preview")
            put("title", presentation.title)
            put("items", JSONArray(presentation.items))
            put("totalCount", presentation.totalCount)
            put("emptyMessage", presentation.emptyMessage)
        }

        is ToolPresentation.ComputedResult -> JSONObject().apply {
            put(KEY_TYPE, "computed_result")
            put("primaryText", presentation.primaryText)
            put("contextText", presentation.contextText)
            put("breakdownText", presentation.breakdownText)
        }
    }

    fun fromJsonObject(obj: JSONObject): ToolPresentation? = when (obj.optString(KEY_TYPE)) {
        "weather" -> ToolPresentation.Weather(
            locationName = obj.optString("locationName"),
            temperatureText = obj.optString("temperatureText"),
            feelsLikeText = obj.optString("feelsLikeText").takeIf { it.isNotBlank() },
            description = obj.optString("description"),
            emoji = obj.optString("emoji"),
            humidityText = obj.optString("humidityText").takeIf { it.isNotBlank() },
            windText = obj.optString("windText").takeIf { it.isNotBlank() },
            precipText = obj.optString("precipText").takeIf { it.isNotBlank() },
            airQualityText = obj.optString("airQualityText").takeIf { it.isNotBlank() },
        )

        "status" -> ToolPresentation.Status(
            icon = obj.optString("icon"),
            title = obj.optString("title"),
            subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() },
        )

        "list_preview" -> ToolPresentation.ListPreview(
            title = obj.optString("title"),
            items = obj.optJSONArray("items")?.let { arr ->
                MutableList(arr.length()) { index -> arr.optString(index) }
            } ?: emptyList(),
            totalCount = obj.optInt("totalCount"),
            emptyMessage = obj.optString("emptyMessage").takeIf { it.isNotBlank() },
        )

        "computed_result" -> ToolPresentation.ComputedResult(
            primaryText = obj.optString("primaryText"),
            contextText = obj.optString("contextText"),
            breakdownText = obj.optString("breakdownText").takeIf { it.isNotBlank() },
        )

        else -> null
    }
}
