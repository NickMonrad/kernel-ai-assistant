package com.kernel.ai.core.skills

import org.json.JSONArray
import org.json.JSONObject

sealed interface ToolPresentation {
    data class ForecastDay(
        val date: String,
        val emoji: String,
        val description: String,
        val highText: String?,
        val lowText: String?,
        val precipText: String?,
        val uvText: String?,
        val sunText: String?,
    )

    data class Weather(
        val locationName: String,
        val temperatureText: String,
        val feelsLikeText: String?,
        val description: String,
        val emoji: String,
        val highLowText: String? = null,
        val humidityText: String?,
        val windText: String?,
        val precipText: String?,
        val uvText: String? = null,
        val airQualityText: String?,
        val sunText: String? = null,
        val forecast: List<ForecastDay> = emptyList(),
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
    private const val KEY_FORECAST = "forecast"
    private const val KEY_DATE = "date"
    private const val KEY_EMOJI = "emoji"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_HIGH_TEXT = "highText"
    private const val KEY_LOW_TEXT = "lowText"
    private const val KEY_PRECIP_TEXT = "precipText"
    private const val KEY_UV_TEXT = "uvText"
    private const val KEY_SUN_TEXT = "sunText"



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
            put("highLowText", presentation.highLowText)
            put("humidityText", presentation.humidityText)
            put("windText", presentation.windText)
            put("precipText", presentation.precipText)
            put("uvText", presentation.uvText)
            put("airQualityText", presentation.airQualityText)
            put("sunText", presentation.sunText)
            if (presentation.forecast.isNotEmpty()) {
                put("forecast", toForecastJson(presentation.forecast))
            }
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
            highLowText = obj.optString("highLowText").takeIf { it.isNotBlank() },
            humidityText = obj.optString("humidityText").takeIf { it.isNotBlank() },
            windText = obj.optString("windText").takeIf { it.isNotBlank() },
            precipText = obj.optString("precipText").takeIf { it.isNotBlank() },
            uvText = obj.optString("uvText").takeIf { it.isNotBlank() },
            airQualityText = obj.optString("airQualityText").takeIf { it.isNotBlank() },
            sunText = obj.optString("sunText").takeIf { it.isNotBlank() },
            forecast = obj.optJSONArray("forecast")?.let { arr ->
                MutableList(arr.length()) { index ->
                    val o = arr.getJSONObject(index)
                    ToolPresentation.ForecastDay(
                        date = o.optString("date"),
                        emoji = o.optString("emoji"),
                        description = o.optString("description"),
                        highText = o.optString("highText").takeIf { it.isNotBlank() },
                        lowText = o.optString("lowText").takeIf { it.isNotBlank() },
                        precipText = o.optString("precipText").takeIf { it.isNotBlank() },
                        uvText = o.optString("uvText").takeIf { it.isNotBlank() },
                        sunText = o.optString("sunText").takeIf { it.isNotBlank() },
                    )
                }
            } ?: emptyList(),
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

    fun toForecastJson(forecast: List<ToolPresentation.ForecastDay>): JSONArray =
        JSONArray(forecast.map { fd ->
            JSONObject().apply {
                put(KEY_DATE, fd.date)
                put(KEY_EMOJI, fd.emoji)
                put(KEY_DESCRIPTION, fd.description)
                put(KEY_HIGH_TEXT, fd.highText ?: "")
                put(KEY_LOW_TEXT, fd.lowText ?: "")
                put(KEY_PRECIP_TEXT, fd.precipText ?: "")
                put(KEY_UV_TEXT, fd.uvText ?: "")
                put(KEY_SUN_TEXT, fd.sunText ?: "")
            }
        })

    fun fromForecastJson(arr: JSONArray): List<ToolPresentation.ForecastDay> =
        MutableList(arr.length()) { index ->
            val o = arr.getJSONObject(index)
            ToolPresentation.ForecastDay(
                date = o.optString(KEY_DATE),
                emoji = o.optString(KEY_EMOJI),
                description = o.optString(KEY_DESCRIPTION),
                highText = o.optString(KEY_HIGH_TEXT).takeIf { it.isNotBlank() },
                lowText = o.optString(KEY_LOW_TEXT).takeIf { it.isNotBlank() },
                precipText = o.optString(KEY_PRECIP_TEXT).takeIf { it.isNotBlank() },
                uvText = o.optString(KEY_UV_TEXT).takeIf { it.isNotBlank() },
                sunText = o.optString(KEY_SUN_TEXT).takeIf { it.isNotBlank() },
            )
        }
}
