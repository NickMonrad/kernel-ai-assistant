package com.kernel.ai.core.voice

/**
 * Conservative card-to-speech cleanup shared by every TTS engine. It handles notation that exists in
 * weather and status cards: emoji, field separators ("|", "/", "·", "•"), line breaks between fields
 * (a sentence pause), em dashes as pauses, "H:17°C" / "L:10°C" / "Humidity: 92%" labels, and the
 * day/month abbreviations and units that [SpeechAbbreviationText] spells out ("Mon 23 Nov", "11°C", "92%").
 * Page links are removed first ([SpeechLinkText], #2041).
 * A bracketed unit conversion speaks only its first unit: "23 km (14 mi)" is spoken as "23 km" (#2192). The screen keeps both.
 *
 * Plain numbers, times and money are deliberately NOT expanded here. Each engine's own preparation owns
 * that (SherpaSpeechText for Piper, EnglishSpeechTextNormaliser for the Kokoro ExecuTorch lane and, through
 * InflectMicroTextFrontend, for Inflect, eSpeak for Android TTS),
 * so nothing is converted twice. The function is idempotent.
 */
object SpeechCardText {
    /** Emoji, variation selectors and joiners, as found in weather and status cards. */
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{3030}\\x{303D}\\x{3297}\\x{3299}\\x{200D}\\x{20E3}\\x{FE0E}\\x{FE0F}]")
    private val arrow = Regex("""\s*[→⇒]\s*""")
    private val highLow = Regex("""\b([HL]):\s*(-?\d+)(\s*°\s*F)?(?:\s*°\s*C)?""")
    private val humidityLabel = Regex("""\bHumidity:\s*(?=\d)""")
    private val lineBreak = Regex("""\s*\r?\n\s*""")
    private val cardSeparator = Regex("""\s+[|/·•]\s+""")
    private val emDash = Regex("""\s*—\s*""")
    private val runOfSpaces = Regex("""[ \t]+""")
    /** Characters that already end a clause, so a line break after them needs no extra full stop. */
    private const val CLAUSE_END = ".!?:;,"
    /**
     * Unit words for a bracketed conversion, singular or plural. "in" is left out: "12 in (30 cm)" would read as a word.
     */
    private const val CONVERSION_UNIT =
        "km/h|mph|km|cm|mm|mg|mL|ml|kg|g|L|lbs?|oz|ft|yds?|mi|m|miles?|kilometres?|kilometers?|metres?|meters?|" +
            "feet|foot|yards?|pounds?|ounces?|inches|inch"
    /** "23 km (14 mi)", "6 ft (1.8 m)": group 1 is the first unit, which is all speech keeps (#2192). */
    private val bracketedConversion = Regex(
        """((?<![\p{L}\p{N}.,:/$])(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?\s*(?:$CONVERSION_UNIT)(?!\p{L}))\s*\(\s*(?:(?:about|approximately|roughly)\s+)?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?\s*(?:$CONVERSION_UNIT)(?!\p{L})\s*\)""",
    )

    fun speakable(text: String): String {
        var value = emoji.replace(SpeechLinkText.strip(text), "")
        value = arrow.replace(value, " to ")
        value = bracketedConversion.replace(value) { match -> match.groupValues[1] }
        value = highLow.replace(value) { m ->
            val unit = if (m.groupValues[3].isNotEmpty()) " Fahrenheit" else ""
            (if (m.groupValues[1] == "H") "high " else "low ") + m.groupValues[2] + unit
        }
        value = humidityLabel.replace(value, "humidity ")
        value = SpeechAbbreviationText.expand(value)
        val beforeBreaks = value
        value = lineBreak.replace(beforeBreaks) { m ->
            // A closing quote or bracket after the mark ("wings.\"") does not hide it: no second full stop.
            if (SpeechTerminalPunctuation.endsWith(beforeBreaks.substring(0, m.range.first), CLAUSE_END)) " " else ". "
        }
        value = cardSeparator.replace(value, ", ")
        value = emDash.replace(value, ", ")
        return runOfSpaces.replace(value, " ").trim()
    }
}
