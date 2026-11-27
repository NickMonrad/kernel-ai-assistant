package com.kernel.ai.debug.inflect

/**
 * Debug-only helpers for the PR #2118 (#2115) listening probes of Inflect number phrases.
 *
 * The probe receiver ([InflectSpikeReceiver]) uses these helpers only when the explicit extras are present.
 * Shipped code never references this object, so it does not ship in release builds.
 */
internal object InflectListeningVariants {
    private val numberWord = Regex(
        "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
            "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|" +
            "thousand|million|billion|trillion|teen)\\b",
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
}
