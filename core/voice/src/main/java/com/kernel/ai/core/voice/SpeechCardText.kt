package com.kernel.ai.core.voice

/**
 * Conservative card-to-speech cleanup shared by every TTS engine. It only handles notation that
 * exists in weather and status cards: emoji, field separators ("|", "/", "·"), line breaks between
 * fields (a sentence pause), em dashes as pauses, and "H:17°C" / "L:10°C" / "Humidity: 92%" labels.
 *
 * Numbers, percentages, dates and times are deliberately NOT expanded here. Each engine's own
 * preparation owns that (SherpaSpeechText for Piper/Kokoro, InflectMicroTextFrontend for Inflect,
 * eSpeak for Android TTS), so nothing is converted twice. The function is idempotent.
 */
object SpeechCardText {
    /** Emoji, variation selectors and joiners, as found in weather and status cards. */
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}\\x{3030}\\x{303D}\\x{3297}\\x{3299}\\x{200D}\\x{20E3}\\x{FE0E}\\x{FE0F}]")
    private val highLow = Regex("""\b([HL]):\s*(-?\d+)(?:\s*°\s*[CF]?)?""")
    private val humidityLabel = Regex("""\bHumidity:\s*(?=\d)""")
    private val lineBreak = Regex("""\s*\r?\n\s*""")
    private val cardSeparator = Regex("""\s+[|/·]\s+""")
    private val emDash = Regex("""\s*—\s*""")
    private val runOfSpaces = Regex("""[ \t]+""")

    fun speakable(text: String): String {
        var value = emoji.replace(text, "")
        value = highLow.replace(value) { m ->
            (if (m.groupValues[1] == "H") "high " else "low ") + m.groupValues[2]
        }
        value = humidityLabel.replace(value, "humidity ")
        value = lineBreak.replace(value, ". ")
        value = cardSeparator.replace(value, ", ")
        value = emDash.replace(value, ", ")
        return runOfSpaces.replace(value, " ").trim()
    }
}
