package com.kernel.ai.core.skills.intent

import java.text.Normalizer

/**
 * Spelling and sound keys for place-name matching (#2079). The sound key is the consonant
 * skeleton under NZ English and Māori sound rules, carried over from the spike resolver.
 */
internal object PlaceSpelling {
    private val DIACRITICS = Regex("\\p{Mn}+")

    /** Lower-case ASCII letters only: macrons and other diacritics stripped, spaces and punctuation removed. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .filter { it in 'a'..'z' }

    /**
     * Consonant skeleton of [text]:
     * - `wh` and `ph` sound as `f`; `ng` is one sound, `N`.
     * - `c` before `e`, `i`, `y` is `s`; other `c`, `k` and `q` are `k`.
     * - Voicing pairs merge: `b` with `p`, `g` with `k`, `v` with `f`, `z` and `j` with `s`.
     * - `d` is `r` (the NZ flap).
     * - Vowels, `y` and `h` are dropped; repeated consonants collapse.
     */
    fun soundKey(text: String): String {
        val letters = fold(text)
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

    /** Levenshtein distance over two strings. */
    fun distance(a: String, b: String): Int {
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

    private fun collapseRepeats(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }
}

/**
 * Ranks a heard place-slot value against NZ place names and abstain competitors (#2079).
 *
 * The heard value is split into word windows of one to [MAX_WINDOW_WORDS] words. Each candidate's
 * distance is the smallest normalised edit distance over its spelling and its sound key, taken
 * over all windows. Competitors (Australian, world and other non-NZ names) take part in ranking
 * so that a closer competitor blocks a prompt; a competitor matching on sound alone does not.
 *
 * [suggest] returns the NZ name to offer as "Did you mean …?" only when it is the closest
 * candidate, within [maxDistance], and beats the runner-up by at least [margin]. It never
 * returns a rewrite; the caller must ask.
 */
internal class PlaceSlotMatcher(
    nzNames: Collection<String>,
    competitorNames: Collection<String> = emptyList(),
    knownNames: Collection<String> = emptyList(),
) {

    private class Candidate(val name: String, val nz: Boolean, val folded: String, val sound: String)

    private class Window(
        val folded: String,
        val sound: String,
        val start: Int,
        val end: Int,
        val words: Int,
        val stopped: Boolean,
    )

    private class Token(val raw: String, val start: Int, val end: Int, val folded: String)

    /**
     * A candidate's distances over the heard windows. [distance] is the smaller of [spelling] (normalised
     * Levenshtein on the folded spelling) and the sound-key distance. [exact] means a window spelled it.
     * [heardRange] is the span of the heard text that the best window covers.
     */
    class Scored(
        val name: String,
        val nz: Boolean,
        val distance: Double,
        val spelling: Double,
        val exact: Boolean,
        val heardRange: IntRange,
    )

    private val candidates: List<Candidate> = run {
        val seen = HashSet<String>()
        (nzNames.map { it to true } + competitorNames.map { it to false })
            .flatMap { (name, nz) ->
                spellingsOf(name, nz).map { Candidate(name, nz, PlaceSpelling.fold(it), PlaceSpelling.soundKey(it)) }
            }
            .filter { it.folded.isNotEmpty() && seen.add(it.folded) }
    }

    /** Folded known-place names (LINZ). A heard window that spells one is never asked about. */
    private val knownFolds: Set<String> = knownNames.map { PlaceSpelling.fold(it) }.filter { it.isNotEmpty() }.toHashSet()

    /** Candidates, closest first. */
    fun rank(heard: String): List<Scored> {
        val windows = windows(heard).filter { !it.stopped }
        if (windows.isEmpty()) return emptyList()
        return candidates
            .map { score(it, windows) }
            .sortedWith(compareBy<Scored>({ it.distance }, { it.spelling }, { !it.nz }, { it.name }))
    }

    /**
     * The NZ name to offer as a suggestion for [heard], or null. Never a rewrite.
     *
     * Competitors take part only when spelling-plausible (spelling within [maxDistance]); a sound-only
     * match neither wins nor blocks. Any exact match, or any window that spells a known place, gives no
     * suggestion. When the top two are tied on
     * distance (within [TIE_EPSILON]), the margin is taken on spelling distance.
     */
    fun suggest(heard: String, maxDistance: Double, margin: Double): Scored? {
        if (windows(heard).any { it.folded in knownFolds }) return null
        val ranked = rank(heard).filter { it.nz || it.spelling <= maxDistance }
        if (ranked.any { it.exact }) return null
        val top = ranked.firstOrNull() ?: return null
        if (!top.nz || top.distance > maxDistance) return null
        val others = ranked.filter { it.name != top.name }
        val runnerUp = others.firstOrNull()?.distance ?: 1.0
        val gap = if (runnerUp - top.distance <= TIE_EPSILON) {
            (others.minOfOrNull { it.spelling } ?: 1.0) - top.spelling
        } else {
            runnerUp - top.distance
        }
        return top.takeIf { gap >= margin }
    }

    private fun windows(heard: String): List<Window> {
        val tokens = WORD.findAll(heard)
            .map { Token(it.value, it.range.first, it.range.last + 1, PlaceSpelling.fold(it.value)) }
            .filter { it.folded.isNotEmpty() }
            .toList()
        val out = ArrayList<Window>()
        for (start in tokens.indices) {
            if (tokens[start].folded in NON_PLACE_LEADING_WORDS) continue
            for (len in 1..MAX_WINDOW_WORDS) {
                if (start + len > tokens.size) break
                val span = tokens.subList(start, start + len)
                val folded = span.joinToString("") { it.folded }
                val stopped = folded in STOP_FOLDED || span.any { POSSESSIVE.containsMatchIn(it.raw) } ||
                    (len == 1 && folded in STOP_PHRASE_PARTS_FOLDED)
                out += Window(
                    folded = folded,
                    sound = PlaceSpelling.soundKey(folded),
                    start = span.first().start,
                    end = span.last().end,
                    words = len,
                    stopped = stopped,
                )
            }
        }
        return out
    }

    private fun score(c: Candidate, windows: List<Window>): Scored {
        var spelling = 1.0
        var sound = 1.0
        var exact = false
        var best: Window? = null
        var bestRank = Double.MAX_VALUE
        for (w in windows) {
            val isExact = w.folded == c.folded
            if (isExact) exact = true
            val spellingHere = if (isExact || (w.folded.length >= MIN_SPELLING_LETTERS && c.folded.length >= MIN_SPELLING_LETTERS)) {
                normalised(w.folded, c.folded)
            } else {
                1.0
            }
            val soundHere = if (w.sound.length >= MIN_SOUND_KEY && c.sound.length >= MIN_SOUND_KEY) {
                normalised(w.sound, c.sound)
            } else {
                1.0
            }
            spelling = minOf(spelling, spellingHere)
            sound = minOf(sound, soundHere)
            // An exact window always wins the span; otherwise the closest window, then the shorter one.
            val here = if (isExact) -1.0 else minOf(spellingHere, soundHere)
            val current = best
            if (current == null || here < bestRank || (here == bestRank && w.words < current.words)) {
                best = w
                bestRank = here
            }
        }
        val span = best?.let { it.start until it.end } ?: IntRange.EMPTY
        if (exact) return Scored(c.name, c.nz, distance = 0.0, spelling = 0.0, exact = true, heardRange = span)
        return Scored(c.name, c.nz, distance = minOf(spelling, sound), spelling = spelling, exact = false, heardRange = span)
    }

    private fun normalised(a: String, b: String): Double =
        PlaceSpelling.distance(a, b).toDouble() / maxOf(a.length, b.length)

    private companion object {
        const val MAX_WINDOW_WORDS = 3
        const val MIN_SPELLING_LETTERS = 4
        const val MIN_SOUND_KEY = 3
        const val TIE_EPSILON = 0.02
        val WORD = Regex("\\S+")
        val POSSESSIVE = Regex("['’]s$", RegexOption.IGNORE_CASE)

        /** "Mt" is the NZ abbreviation of "Mount", so an NZ "Mount X" is also offered under "Mt X". */
        fun spellingsOf(name: String, nz: Boolean): List<String> =
            if (nz && name.startsWith("Mount ")) listOf(name, "Mt " + name.removePrefix("Mount ")) else listOf(name)

        /** First words that make a phrase a description rather than a place name. */
        val NON_PLACE_LEADING_WORDS = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
            "to", "at", "in", "on", "from", "for", "of", "near", "home", "work",
        )

        /** Generic destinations, common nouns and brands that are never the place in a navigate or weather slot (#2079). */
        val GENERIC_DESTINATIONS = listOf(
            "doctor", "doctors", "dentist", "hospital", "pharmacy", "chemist", "bank", "atm", "post office",
            "library", "church", "kindergarten", "daycare", "gym", "mall", "supermarket", "countdown", "new world",
            "pak n save", "paknsave", "warehouse", "bunnings", "park", "holiday park", "playground", "beach",
            "airport", "station", "train station", "bus stop", "petrol station", "gas station", "service station",
            "cafe", "coffee shop", "bakery", "butcher", "shop", "shops", "store", "pub", "bar", "restaurant",
            "cinema", "movies", "theatre", "stadium", "zoo", "museum", "markets", "farmers market", "dairy",
            "bottle store", "liquor store", "bottle shop", "hardware store", "hairdresser", "barber", "vet",
            "mechanic", "garage", "car wash", "dump", "tip", "council", "marae", "farm", "cemetery", "hall",
            "community hall", "club", "golf course", "pool", "swimming pool", "lake", "river", "bridge", "bush",
            "forest", "reserve", "domain", "motorway", "highway", "hotel", "motel", "campsite", "hostel", "city",
            "city centre", "town centre", "cbd", "downtown", "wharf", "port", "lookout", "viewpoint", "falls",
            "hot pools", "hot springs", "cave", "carpark", "car park", "parking lot", "depot", "gate", "marina",
            "office", "school", "college", "university", "home", "house", "work",
            "factory", "clinic", "physio", "physiotherapy", "physio clinic", "courthouse", "police", "police station",
            "fire station", "sports", "centre", "sports centre", "community centre", "medical centre", "health centre",
        )

        /** Common first names and family words. A bare name, or "<name> place" and "<name> house", is a person. */
        val PEOPLE = listOf(
            "michael", "tom", "john", "david", "sarah", "emma", "jess", "sam", "mike", "dan", "chris", "james",
            "jack", "liam", "olivia", "lucy", "anna", "kate", "ben", "matt", "steve", "paul", "mark", "andrew",
            "peter", "grace", "aroha", "hine", "mere", "wiremu", "mum", "dad", "nana", "grandma", "sister",
            "brother", "friend",
        ).flatMap { listOf(it, "${it}s") }

        /** Folded stop set: generic destinations, people, and people with "place" or "house". */
        val STOP_FOLDED: Set<String> =
            (GENERIC_DESTINATIONS + PEOPLE + PEOPLE.flatMap { listOf("$it place", "$it house") })
                .map { PlaceSpelling.fold(it) }
                .toHashSet()

        /** Single words of multi-word generic phrases ("petrol" of "petrol station"): a lone one is never a place. */
        val STOP_PHRASE_PARTS_FOLDED: Set<String> =
            GENERIC_DESTINATIONS.filter { it.contains(' ') }
                .flatMap { it.split(' ') }
                .map { PlaceSpelling.fold(it) }
                .toHashSet()
    }
}
