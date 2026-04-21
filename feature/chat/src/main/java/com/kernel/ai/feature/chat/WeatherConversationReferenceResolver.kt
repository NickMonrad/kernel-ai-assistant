package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.feature.chat.model.ChatMessage
import org.json.JSONObject

internal object WeatherConversationReferenceResolver {
    private val WEATHER_QUERY_REGEX = Regex(
        """\b(weather|forecast|temperature|rain|raining|umbrella|uv|air quality|sunrise|sunset)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DEICTIC_REFERENCE_REGEX = Regex(
        """\b(there|that\s+(?:city|place|town|location|capital))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CAPITAL_ANSWER_REGEX = Regex(
        """\b([A-Z][A-Za-z'\- ]+?)\s+is\s+the\s+capital(?:\s+city)?\s+of\b""",
    )
    private val CAPITAL_ANSWER_REVERSED_REGEX = Regex(
        """\bthe\s+capital(?:\s+city)?\s+of\s+.+?\s+is\s+([A-Z][A-Za-z'\- ]+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val WEATHER_LOCATION_REGEX = Regex(
        """\bweather(?:\s+(?:in|for|at))\s+([A-Z][A-Za-z'\- ,]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val REQUEST_LOCATION_REGEX = Regex(
        """location\s*[:=]\s*["']?([^,"'}\]]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun resolveLocation(query: String, messages: List<ChatMessage>): String? {
        if (!looksLikeFollowUpWeatherQuery(query)) return null
        return messages.asReversed().firstNotNullOfOrNull(::extractLocation)
    }

    private fun looksLikeFollowUpWeatherQuery(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.contains("out there", ignoreCase = true)) return false
        return WEATHER_QUERY_REGEX.containsMatchIn(trimmed) &&
            DEICTIC_REFERENCE_REGEX.containsMatchIn(trimmed)
    }

    private fun extractLocation(message: ChatMessage): String? {
        val presentationLocation = (message.toolCall?.presentation as? ToolPresentation.Weather)
            ?.locationName
            ?.takeIf { it.isNotBlank() }
        if (presentationLocation != null) return presentationLocation

        val requestLocation = message.toolCall
            ?.requestJson
            ?.let(::extractLocationFromRequest)
        if (requestLocation != null) return requestLocation

        return extractLocationFromText(message.content)
    }

    private fun extractLocationFromRequest(requestJson: String): String? {
        runCatching {
            return JSONObject(requestJson).optString("location").takeIf { it.isNotBlank() }
        }
        return REQUEST_LOCATION_REGEX.find(requestJson)?.groupValues?.get(1)?.trim()
    }

    private fun extractLocationFromText(text: String): String? {
        CAPITAL_ANSWER_REGEX.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        CAPITAL_ANSWER_REVERSED_REGEX.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        WEATHER_LOCATION_REGEX.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        return null
    }
}
