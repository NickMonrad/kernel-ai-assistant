package com.kernel.ai.debug.inflect

/**
 * Debug-only switches for the PR #2118 (#2115) listening variants of Inflect number phrases.
 *
 * The probe receiver ([InflectSpikeReceiver]) uses these helpers only when the explicit extras are present.
 * The shipped frontend and controller are unchanged. Not for release builds.
 */
internal object InflectListeningVariants {
    private val numberWord = Regex(
        "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
            "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|" +
            "thousand|million|billion|trillion)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Chunk separator for the `segments` extra. Each segment is normalised and synthesised separately, in order. */
    fun parseSegments(raw: String): List<String> = raw.split("|").map(String::trim).filter(String::isNotEmpty)

    /** `from=to` pairs separated by `|`, applied to eSpeak phonemes after the production override pass. */
    fun parseOverrides(raw: String?): List<Pair<String, String>> =
        raw?.split("|")?.filter { it.contains("=") }?.map { pair ->
            val (from, to) = pair.split("=", limit = 2)
            from to to
        }.orEmpty()

    fun containsNumberWord(text: String): Boolean = numberWord.containsMatchIn(text)

    /** Teen treatments for 13..19. Each rewrites the raw eSpeak `…tiːn` token of the teen word, nothing else. */
    enum class TeenTreatment { V5, TEEN_LONG, FIF_TEEN, COMMA, SCHWA }

    private val teenToken = Regex("t[ˈˌ]?iːn\\b")
    private val teenTokenWithOnsetStress = Regex("([^\\s]*?)t[ˈˌ]?iːn\\b")

    fun rewriteTeen(treatment: TeenTreatment, phonemes: String): String = when (treatment) {
        TeenTreatment.V5 -> phonemes.replace(teenToken, "tˈiːn")
        TeenTreatment.TEEN_LONG -> phonemes.replace(teenTokenWithOnsetStress) { match ->
            match.groupValues[1].replace("ˈ", "").replace("ˌ", "") + "tˈiːːn"
        }
        TeenTreatment.FIF_TEEN -> phonemes.replace(teenToken, " tˈiːn")
        TeenTreatment.COMMA -> phonemes.replace(teenToken, "tˈiːn,")
        TeenTreatment.SCHWA -> phonemes.replace(teenToken, "tˈiːnə")
    }
}
