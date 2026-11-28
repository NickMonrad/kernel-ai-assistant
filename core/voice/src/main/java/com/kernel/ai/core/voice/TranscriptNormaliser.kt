package com.kernel.ai.core.voice

import android.util.Log

/**
 * Pure-Kotlin normaliser that runs on every STT transcript *before* it reaches
 * any downstream consumer (intent router, LLM, RAG, chat, slot fill).
 *
 * Alias and correction tables, applied in this order by [normalise]:
 * - [WEATHER_MISHEAR_REPLACEMENTS] — context-gated "weather" mishears (#1940)
 * - [AUCKLAND_REPLACEMENTS] — Auckland mishears (#1894)
 * - [KIWI_PHONETIC_REPLACEMENTS] — word-level Kiwi/Māori mishears (#935, #939, #1852)
 * - [STT_UNIT_ALIAS_REPLACEMENTS] — unit/abbreviation normalisations (#1017)
 * - [MISHEARD_ADD_LIST_COMMAND] — "add" misheard as "I've"/"I'd"/"ad" in list commands (#1820)
 * - [US_TO_NZ_SPELLING] — US→NZ spelling of words the recogniser writes (#2177)
 *
 * ## False-positive note
 * `comrade` → `kumara` is a known limitation: "i was a comrade in arms" becomes
 * "i was a kumara in arms". The map is intentionally narrow (only confirmed STT
 * mishears from #935 and its comment), and the corpus terms for these words
 * (wharepaku, taniwha, kumara, etc.) don't currently exist in
 * `nz_truth_memories.json`, so we have no disambiguation signal to draw on.
 * This substitution is logged at `Log.WARN` for observability.
 *
 * ## Scope
 * - `lost`/`lust`/`last` → `list` is intentionally **not** here — see
 *   `QuickIntentRouter.LIST_NAME_TAIL_MISHEAR_RE` (#982). That substitution is
 *   high-risk and must never corrupt chat copy ("I lost my keys").
 * - `mil` is intentionally omitted — ambiguous (millilitre vs thousandth of an
 *   inch, see #1017).
 */
object TranscriptNormaliser {

    private const val TAG = "TranscriptNormaliser"

    /** Word-level Kiwi/Māori mishear corrections: #935, #939. */
    private val KIWI_PHONETIC_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // #939 — wharepaku <-> fattybaku / fattypaku
        Regex("""\bfattybaku\b""", RegexOption.IGNORE_CASE) to "wharepaku",
        Regex("""\bfattypaku\b""", RegexOption.IGNORE_CASE) to "wharepaku",
        Regex("""\bfarah\s+paco\b""", RegexOption.IGNORE_CASE) to "wharepaku",
        // #935 / #935-comment — taniwha
        Regex("""\btonifa\b""", RegexOption.IGNORE_CASE) to "taniwha",
        Regex("""\btanifa\b""", RegexOption.IGNORE_CASE) to "taniwha",
        // #935-comment — kumara
        Regex("""\bcomrade\b""", RegexOption.IGNORE_CASE) to "kumara",
        // #935-comment — chocka
        Regex("""\bchaka\b""", RegexOption.IGNORE_CASE) to "chocka",
        // #935-comment — kumara (observed device mishears)
        Regex("""\bkumada\b""", RegexOption.IGNORE_CASE) to "kumara",
        Regex("""\bcumbra\b""", RegexOption.IGNORE_CASE) to "kumara",
        Regex("""\bcornbra\b""", RegexOption.IGNORE_CASE) to "kumara",
        // #939 — wharepaku <-> fatty pucker / fattypucker (observed device mishears)
        Regex("""\bfatty\s+pucker\b""", RegexOption.IGNORE_CASE) to "wharepaku",
        Regex("""\bfattypucker\b""", RegexOption.IGNORE_CASE) to "wharepaku",
        // #1852 — Petone heard as "patroni" (Zipformer). Not an English word.
        Regex("""\bpatroni\b""", RegexOption.IGNORE_CASE) to "Petone",
    )

    /** Auckland mishears from STT: #1894. Whole-word only; "Oakland" is deliberately untouched. */
    private val AUCKLAND_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // "weather an <Auckland-ish>" → "weather in Auckland" (only the weather phrasing; "an" is never the intended word here)
        Regex("""\b(weather)\s+an\s+(?:aukland|orkland|ockland|auckland)\b""", RegexOption.IGNORE_CASE) to "$1 in Auckland",
        Regex("""\baukland\b""", RegexOption.IGNORE_CASE) to "Auckland",
        Regex("""\borkland\b""", RegexOption.IGNORE_CASE) to "Auckland",
        Regex("""\bockland\b""", RegexOption.IGNORE_CASE) to "Auckland",
    )

    /**
     * Weather-keyword mishears from STT: #1940. Voice-transcript only (called from the STT
     * controllers, never from typed input). A correction fires only in a weather-shaped
     * question: question head ("what's", "whats", "how's", "what is") + "the" + mishear
     * (whither/wither/wether/whether/wayther), followed by a weather follower or end of
     * utterance. "the way" is accepted only as "the way" + a time phrase that ends the
     * utterance. A bare "the wether" (the NZ farm word for a castrated sheep) is never rewritten.
     */
    private const val WEATHER_FOLLOWER =
        """(?:in|for|at|like|looking|on|going\s+to|now|right\s+now|currently|out\s+there|here|over|today|tonight|tomorrow|outside|forecast|this\s+(?:morning|afternoon|evening|week|weekend)|next\s+(?:week|weekend|(?:few|one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+days))"""
    private const val WEATHER_TIME_FOLLOWER =
        """(?:today|tonight|tomorrow|forecast|this\s+(?:morning|afternoon|evening|week|weekend)|next\s+(?:week|weekend|(?:few|one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+days))"""
    /** Optional spoken filler before the question ("hey jandal, what's the wether…"). Anchors the rule to utterance start. */
    private const val WEATHER_LEAD_IN = """^(\s*(?:(?:hey|hi|ok|okay)(?:\s+jandal)?[,\s]+)?)"""
    private const val WEATHER_QUESTION_HEAD = """(what(?:['’]?s|\s+is)?|how(?:['’]?s|\s+is)?)"""
    private const val WEATHER_MISHEAR = """(?:whither|wither|wether|whether|wayther)"""

    private val WEATHER_MISHEAR_REPLACEMENTS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // "what the whither in Auckland", "how's the wether tomorrow", "what's the whether like today"
        Regex(
            """$WEATHER_LEAD_IN$WEATHER_QUESTION_HEAD\s+the\s+$WEATHER_MISHEAR\b(?=\s+$WEATHER_FOLLOWER\b|\s*[.!?]*$)""",
            RegexOption.IGNORE_CASE,
        ) to { mr -> "${mr.groupValues[1]}${questionHead(mr.groupValues[2])} the weather" },
        // "what's the way tomorrow" — "the way" alone is navigation ("the way to"), so require a
        // time phrase that ends the utterance
        Regex(
            """$WEATHER_LEAD_IN$WEATHER_QUESTION_HEAD\s+the\s+way(?=\s+$WEATHER_TIME_FOLLOWER\s*[.!?]*$)""",
            RegexOption.IGNORE_CASE,
        ) to { mr -> "${mr.groupValues[1]}${questionHead(mr.groupValues[2])} the weather" },
    )

    /** "what"/"whats" → "what's", "how"/"hows" → "how's"; keeps original first-letter casing and an existing "'s"/"is". */
    private fun questionHead(lead: String): String = when {
        lead.equals("whats", ignoreCase = true) -> "${lead.take(4)}'s"
        lead.equals("hows", ignoreCase = true) -> "${lead.take(3)}'s"
        Regex("""\s+is$|['’]s$""", RegexOption.IGNORE_CASE).containsMatchIn(lead) -> lead
        else -> "${lead}'s"
    }

    /** Unit/abbreviation normalisations: #1017. */
    private val STT_UNIT_ALIAS_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        // #1017 — mls / Mills / mils / ml's -> ml
        Regex("""\b(\d+(?:\.\d+)?)\s*mills\b""", RegexOption.IGNORE_CASE) to "$1 ml",
        Regex("""\b(\d+(?:\.\d+)?)\s*mls\b""", RegexOption.IGNORE_CASE) to "$1 ml",
        Regex("""\b(\d+(?:\.\d+)?)\s*mils\b""", RegexOption.IGNORE_CASE) to "$1 ml",
        Regex("""\b(\d+(?:\.\d+)?)\s*ml's\b""", RegexOption.IGNORE_CASE) to "$1 ml",
        // bare capitalized Mills / MLS when preceded by digit
        Regex("""(\d+(?:\.\d+)?)\s+Mills\b""") to "$1 ml",
    )

    /**
     * #1820: a leading "add" misheard as "I've" / "I'd" / "ad" in a whole-utterance list
     * command ("i've milk to my shopping list"). Only the full command shape is rewritten,
     * and a following word that makes it an ordinary sentence ("I'd like …", "I've got …")
     * leaves it alone.
     */
    private val MISHEARD_ADD_LIST_COMMAND = Regex(
        """^\s*(?:i['’]ve|i['’]d|ad)\s+(.{1,40}?)\s+to\s+((?:my|the)\s+(?:[\w'’-]+\s+){0,3}list)\s*([.!?]*)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words that, straight after "I've"/"I'd", make an ordinary sentence rather than a list
     * item ("I'd stick to the list", "I've come back to my list"). Any word ending in -ed or
     * -ing is treated the same way.
     */
    private val MISHEARD_ADD_VERB_STARTS = setOf(
        "a", "add", "agree", "already", "also", "be", "been", "better", "come", "go", "gone",
        "got", "had", "have", "head", "just", "keep", "like", "listen", "look", "love", "move",
        "need", "never", "point", "prefer", "put", "rather", "refer", "return", "run", "say",
        "send", "sent", "stay", "stick", "stuck", "switch", "talk", "to", "turn", "want",
        "went", "write",
    )

    private fun looksLikeVerbStart(item: String): Boolean {
        val first = item.trim().substringBefore(' ').lowercase().trim('\'', '’')
        return first in MISHEARD_ADD_VERB_STARTS || first.endsWith("ed") || first.endsWith("ing")
    }

    /**
     * US→NZ spelling (#2177): the recogniser writes US forms ("mom", "color") in an en-NZ app. Whole words only; every
     * pair is a form that is never the intended NZ word. The heard word's case carries over: "Mom" → "Mum", "MOM" → "MUM".
     * Left out as unsafe: "center", "gray" (names), "meter" (NZ electricity meter), "license", "tire", "honor" (the
     * device brand).
     */
    private val US_TO_NZ_SPELLING: List<Pair<Regex, String>> = listOf(
        "mommies" to "mummies", "mommy" to "mummy", "moms" to "mums", "mom" to "mum",
        "colors" to "colours", "color" to "colour", "colored" to "coloured",
        "favorites" to "favourites", "favorite" to "favourite",
        "flavors" to "flavours", "flavor" to "flavour", "humor" to "humour",
        "neighbors" to "neighbours", "neighbor" to "neighbour",
        "neighborhoods" to "neighbourhoods", "neighborhood" to "neighbourhood",
        "theaters" to "theatres", "theater" to "theatre", "jewelry" to "jewellery",
        "traveled" to "travelled", "traveling" to "travelling",
        "travelers" to "travellers", "traveler" to "traveller",
        "canceled" to "cancelled", "canceling" to "cancelling",
        "labeled" to "labelled", "labeling" to "labelling",
    ).map { (us, nz) -> Regex("""\b${Regex.escape(us)}\b""", RegexOption.IGNORE_CASE) to nz }

    /** Gives [nz] the case shape of the [heard] word: all caps, capitalised, or as written. */
    private fun matchCase(heard: String, nz: String): String = when {
        heard.length > 1 && heard.all { it.isUpperCase() } -> nz.uppercase()
        heard[0].isUpperCase() -> nz.replaceFirstChar { it.uppercaseChar() }
        else -> nz
    }

    /**
     * Normalise [text] by applying all replacement rules in order.
     *
     * - Returns [text] unchanged if blank.
     * - Idempotent: `normalise(normalise(x)) == normalise(x)`.
     * - Does not collapse whitespace — downstream consumers handle spacing.
     * - Drops trailing sentence punctuation and whitespace before the rules run.
     */
    fun normalise(text: String): String {
        if (text.isBlank()) return text
        var out = stripTrailingSentencePunctuation(text)
        for ((re, replacement) in WEATHER_MISHEAR_REPLACEMENTS) {
            out = re.replace(out, replacement)
        }
        for ((re, replacement) in AUCKLAND_REPLACEMENTS) {
            out = re.replace(out, replacement)
        }
        for ((re, replacement) in KIWI_PHONETIC_REPLACEMENTS) {
            val before = out
            out = re.replace(out, replacement)
            if (out != before) {
                Log.w(TAG, "KIWI_PHONETIC applied to \"$before\" → \"$out\"")
            }
        }
        for ((re, replacement) in STT_UNIT_ALIAS_REPLACEMENTS) {
            out = re.replace(out, replacement)
        }
        MISHEARD_ADD_LIST_COMMAND.matchEntire(out)?.takeUnless { looksLikeVerbStart(it.groupValues[1]) }?.let { m ->
            val before = out
            out = "add ${m.groupValues[1]} to ${m.groupValues[2]}${m.groupValues[3]}"
            Log.w(TAG, "MISHEARD_ADD applied to \"$before\" → \"$out\"")
        }
        for ((re, replacement) in US_TO_NZ_SPELLING) {
            out = re.replace(out) { match -> matchCase(match.value, replacement) }
        }
        return out
    }

    private val DOTTED_MERIDIEMS = setOf("a.m.", "p.m.")

    /**
     * Drops the sentence punctuation and whitespace a recogniser appends ("Set a timer."). Only the tail
     * is touched, so interior decimals and clock times ("3.5", "5.30") survive. A dotted meridiem keeps
     * its final dot ("set an alarm for 7 p.m."); the alarm parser accepts it with or without the dot.
     * Char predicate, not regex: Android ICU regex differs from the JVM (#1968).
     */
    fun stripTrailingSentencePunctuation(text: String): String {
        val keepingDot = text.trimEnd { it == '!' || it == '?' || it.isWhitespace() }
        if (keepingDot.takeLast(4).lowercase() in DOTTED_MERIDIEMS) return keepingDot
        return text.trimEnd { it == '.' || it == '!' || it == '?' || it.isWhitespace() }
    }
}
