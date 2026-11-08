package com.kernel.ai.core.skills.natives

internal object WeatherLocationReferenceParser {
    private val CAPITAL_OF_REGEX = Regex(
        """(?:^|\b)(?:the\s+)?capital(?:\s+city)?\s+of\s+(.+?)(?:\s+(?:today|tonight|now|forecast))?[?.!]*$""",
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

    private val KNOWN_CAPITALS = mapOf(
        "New Zealand" to "Wellington",
        "Australia" to "Canberra",
        "United States" to "Washington, D.C.",
        "United Kingdom" to "London",
        "Ireland" to "Dublin",
        "Canada" to "Ottawa",
        "France" to "Paris",
        "Germany" to "Berlin",
        "Italy" to "Rome",
        "Spain" to "Madrid",
        "Portugal" to "Lisbon",
        "Netherlands" to "Amsterdam",
        "Belgium" to "Brussels",
        "Switzerland" to "Bern",
        "Austria" to "Vienna",
        "Japan" to "Tokyo",
        "China" to "Beijing",
        "India" to "New Delhi",
        "Singapore" to "Singapore",
        "United Arab Emirates" to "Abu Dhabi",
    )

    fun extractCountryFromCapitalQuery(raw: String): String? {
        val match = CAPITAL_OF_REGEX.find(raw.trim()) ?: return null
        val country = match.groupValues[1].trim().trimEnd('?', '.', '!')
        return normalizeCountryName(country)
    }

    fun normalizeCountryName(raw: String): String {
        val trimmed = raw.trim()
        val alias = COUNTRY_ALIASES[trimmed.lowercase()]
        return alias ?: trimmed
    }

    fun knownCapitalForCountry(raw: String): String? = KNOWN_CAPITALS[normalizeCountryName(raw)]
}
