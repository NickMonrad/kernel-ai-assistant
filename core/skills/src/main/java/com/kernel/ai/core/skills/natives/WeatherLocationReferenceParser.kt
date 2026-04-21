package com.kernel.ai.core.skills.natives

internal object WeatherLocationReferenceParser {
    private val CAPITAL_OF_REGEX = Regex(
        """^(?:the\s+)?capital(?:\s+city)?\s+of\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val COUNTRY_ALIASES = mapOf(
        "nz" to "New Zealand",
        "n.z." to "New Zealand",
        "uk" to "United Kingdom",
        "u.k." to "United Kingdom",
        "usa" to "United States",
        "u.s.a." to "United States",
        "us" to "United States",
        "u.s." to "United States",
        "uae" to "United Arab Emirates",
        "u.a.e." to "United Arab Emirates",
    )

    fun extractCountryFromCapitalQuery(raw: String): String? {
        val match = CAPITAL_OF_REGEX.matchEntire(raw.trim()) ?: return null
        val country = match.groupValues[1].trim().trimEnd('?', '.', '!')
        return normalizeCountryName(country)
    }

    fun normalizeCountryName(raw: String): String {
        val trimmed = raw.trim()
        val alias = COUNTRY_ALIASES[trimmed.lowercase()]
        return alias ?: trimmed
    }
}
