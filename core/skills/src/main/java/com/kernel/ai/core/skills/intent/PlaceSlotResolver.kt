package com.kernel.ai.core.skills.intent

import java.text.Normalizer

/**
 * Snaps a place-slot value to a NZ gazetteer name when it spells or sounds like one (#2079).
 *
 * Only the place slots listed in [SLOTS] are read or rewritten, so ordinary text in other
 * slots, and any text outside a slot, is never changed. Two matchers, tried in order:
 *
 * 1. Spelling: a heard value within a length-scaled edit distance of exactly one name.
 * 2. Sound: a heard value whose [phoneticKey] equals, or is within one edit of, exactly one
 *    name's key. The key is a consonant skeleton with NZ/Māori sound rules applied, so
 *    "funga day" reaches "Whangārei" and "pararapr" reaches "Paraparaumu".
 *
 * Guards: the heard value must be at most [MAX_HEARD_WORDS] words and at least
 * [MIN_HEARD_LETTERS] letters, and must not begin with a determiner or preposition ("the beach"
 * is not a place). A tie at either step returns no match. An exact spelling (case and macrons
 * folded) always returns its canonical name.
 */
internal object PlaceSlotResolver {

    /** Intent name to the place-slot parameter it fills. */
    private val SLOTS = mapOf(
        "navigate_to" to "destination",
        "get_weather" to "location",
        "get_time" to "location",
    )

    private const val MIN_HEARD_LETTERS = 6
    private const val MAX_HEARD_WORDS = 3
    private const val MIN_KEY_LENGTH = 3

    /** First words that make a phrase a description rather than a place name. */
    private val NON_PLACE_LEADING_WORDS = setOf(
        "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
        "to", "at", "in", "on", "from", "for", "of", "near", "home", "work",
    )

    // Declared before the keys below: object properties initialise in order.
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Common NZ towns and cities, in the spelling Jandal uses. Keep in sync with the STT glossary. */
    internal val GAZETTEER: List<String> = listOf(
        "Auckland", "Wellington", "Christchurch", "Hamilton", "Tauranga", "Napier", "Hastings",
        "Dunedin", "Palmerston North", "Nelson", "Rotorua", "New Plymouth", "Whangārei",
        "Invercargill", "Whanganui", "Gisborne", "Porirua", "Upper Hutt", "Lower Hutt", "Timaru",
        "Blenheim", "Taupō", "Pūkekohe", "Paraparaumu", "Masterton", "Levin", "Ōtaki", "Waikanae",
        "Feilding", "Whakatāne", "Cambridge", "Te Awamutu", "Matamata", "Morrinsville", "Thames",
        "Paeroa", "Tokoroa", "Tūrangi", "Taihape", "Hāwera", "Raglan", "Greymouth", "Westport",
        "Hokitika", "Queenstown", "Wānaka", "Alexandra", "Ōamaru", "Balclutha", "Gore", "Ashburton",
        "Rangiora", "Kaikōura", "Motueka", "Stratford", "Waitara", "Kerikeri", "Te Puke",
        "Ōpōtiki", "Wairoa", "Papakura", "Whitianga", "Dargaville", "Kaitaia", "Havelock North",
    )

    private val gazetteerKeys: List<Gazetteered> = GAZETTEER.map {
        Gazetteered(name = it, folded = foldKey(it), sound = phoneticKey(it))
    }

    private class Gazetteered(val name: String, val folded: String, val sound: String)

    /** Returns [params] with the place slot for [intentName] snapped to the gazetteer, or unchanged. */
    fun resolve(intentName: String, params: Map<String, String>): Map<String, String> {
        val slot = SLOTS[intentName] ?: return params
        val heard = params[slot] ?: return params
        val canonical = snap(heard) ?: return params
        return params + (slot to canonical)
    }

    /** The unique gazetteer name [heard] spells or sounds like, or null. */
    internal fun snap(heard: String): String? {
        val words = heard.trim().split(WHITESPACE)
        if (words.size > MAX_HEARD_WORDS) return null
        if (words.first().lowercase() in NON_PLACE_LEADING_WORDS) return null
        val folded = foldKey(heard)
        // An exact spelling only needs the canonical macrons; short names are allowed here.
        gazetteerKeys.firstOrNull { it.folded == folded }?.let { return it.name }
        if (folded.length < MIN_HEARD_LETTERS) return null
        bestUnique(gazetteerKeys) { levenshtein(folded, it.folded) <= maxSpellingDistance(it.folded.length) }
            ?.let { return it.name }
        val sound = phoneticKey(heard)
        if (sound.length < MIN_KEY_LENGTH) return null
        return bestUnique(gazetteerKeys) { candidate ->
            candidate.sound.length >= MIN_KEY_LENGTH &&
                levenshtein(sound, candidate.sound) <= maxSoundDistance(candidate.sound.length)
        }?.name
    }

    /** The one candidate that passes [accept]; null if none or more than one. */
    private fun bestUnique(candidates: List<Gazetteered>, accept: (Gazetteered) -> Boolean): Gazetteered? =
        candidates.filter(accept).singleOrNull()

    /** Edit allowance scales with name length so short names cannot absorb unrelated words. */
    private fun maxSpellingDistance(nameLength: Int): Int = when {
        nameLength <= 7 -> 1
        nameLength <= 10 -> 2
        else -> 3
    }

    /** Sound keys are short consonant skeletons, so one edit is the most they may differ by. */
    private fun maxSoundDistance(keyLength: Int): Int = if (keyLength >= 5) 1 else 0

    /** Lower-case ASCII letters only, macrons and other diacritics stripped, spaces removed. */
    private fun foldKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .filter { it in 'a'..'z' }

    /**
     * A consonant skeleton of [text] under NZ English and Māori sound rules:
     * - `wh` and `ph` sound as `f`; `ng` is one sound, `N`.
     * - `c` before `e`, `i`, `y` is `s`; other `c` and `k` and `q` are `k`; `ck` is `k`.
     * - Voicing pairs merge: `b` with `p`, `g` with `k`, `v` with `f`, `z` and `j` with `s`.
     * - `d` is `r` (the NZ flap, so "day" matches "rei").
     * - Vowels, `y` and `h` are dropped, so vowel length, macrons and dropped final vowels do not count.
     * - Repeated consonants collapse, so "rr" counts once.
     */
    internal fun phoneticKey(text: String): String {
        val letters = foldKey(text)
        val out = StringBuilder(letters.length)
        var i = 0
        while (i < letters.length) {
            val c = letters[i]
            val next = letters.getOrNull(i + 1)
            when {
                (c == 'w' || c == 'p') && next == 'h' -> { out.append('f'); i += 2; continue }
                c == 'n' && next == 'g' -> { out.append('N'); i += 2; continue }
                c == 'c' && next == 'k' -> { out.append('k'); i += 2; continue }
                c == 'c' -> out.append(if (next != null && next in "eiy") 's' else 'k')
                c == 'q' -> out.append('k')
                c == 'b' -> out.append('p')
                c == 'g' -> out.append('k')
                c == 'v' -> out.append('f')
                c == 'z' || c == 'j' -> out.append('s')
                c == 'd' -> out.append('r')
                c in "aeiouyh" -> Unit
                else -> out.append(c)
            }
            i++
        }
        return collapseRepeats(out.toString())
    }

    private fun collapseRepeats(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
