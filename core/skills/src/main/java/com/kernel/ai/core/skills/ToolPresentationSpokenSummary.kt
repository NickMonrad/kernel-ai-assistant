package com.kernel.ai.core.skills

/**
 * Converts a [ToolPresentation] into a concise, listener-friendly spoken summary for TTS.
 *
 * Display text is formatted for on-screen reading: emojis, compact units like `25°C`, multi-line
 * layout, and terse separators like `H 27°C / L 18°C`. TTS reads all of that literally.
 *
 * Fallback order for voice surfaces:
 * 1. skill-provided `spokenSummary`
 * 2. [ToolPresentation.toSpokenSummary]
 * 3. generic display-text truncation in the caller
 */
fun ToolPresentation.toSpokenSummary(): String? = when (this) {
    is ToolPresentation.Weather -> toSpokenSummary()
    is ToolPresentation.Status,
    is ToolPresentation.ListPreview,
    is ToolPresentation.ComputedResult,
    -> null
}

fun ToolPresentation.Weather.toSpokenSummary(): String = buildString {
    val cityName = locationName.substringBefore(",").trim().takeIf { it.isNotBlank() } ?: locationName
    val expandedTemp = temperatureText.expandTemperatureUnits()
    append("In $cityName, it's $expandedTemp")
    if (description.isNotBlank() && description != "Unknown") {
        append(", $description")
    }
    feelsLikeText?.let { feels ->
        append(", ${feels.expandTemperatureUnits().replaceFirstChar { it.lowercase() }}")
    }
    append(".")
}

internal fun String.expandTemperatureUnits(): String =
    replace(Regex("""(-?\d+)°C"""), "$1 degrees Celsius")
        .replace(Regex("""(-?\d+)°F"""), "$1 degrees Fahrenheit")
