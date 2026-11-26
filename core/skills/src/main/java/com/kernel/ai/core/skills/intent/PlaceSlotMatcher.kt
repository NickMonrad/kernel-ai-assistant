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
 * so that a closer competitor blocks a prompt.
 *
 * [suggest] returns the NZ name to offer as "Did you mean …?" only when it is the closest
 * candidate, within [maxDistance], and beats the runner-up by at least [margin]. It never
 * returns a rewrite; the caller must ask.
 */
internal class PlaceSlotMatcher(nzNames: Collection<String>, competitorNames: Collection<String> = emptyList()) {

    private class Candidate(val name: String, val nz: Boolean, val folded: String, val sound: String)

    private class Window(val folded: String, val sound: String)

    /** A candidate's best distance over the heard windows. [exact] means a window spelled it. */
    class Scored(val name: String, val nz: Boolean, val distance: Double, val exact: Boolean)

    private val candidates: List<Candidate> = run {
        val seen = HashSet<String>()
        (nzNames.map { it to true } + competitorNames.map { it to false })
            .map { (name, nz) -> Candidate(name, nz, PlaceSpelling.fold(name), PlaceSpelling.soundKey(name)) }
            .filter { it.folded.isNotEmpty() && seen.add(it.folded) }
    }

    /** Candidates, closest first. */
    fun rank(heard: String): List<Scored> {
        val windows = windows(heard)
        if (windows.isEmpty()) return emptyList()
        return candidates
            .map { score(it, windows) }
            .sortedWith(compareBy<Scored>({ it.distance }, { !it.nz }, { it.name }))
    }

    /** The NZ name to offer as a suggestion for [heard], or null. Never a rewrite. */
    fun suggest(heard: String, maxDistance: Double, margin: Double): Scored? {
        val ranked = rank(heard)
        val top = ranked.firstOrNull() ?: return null
        if (!top.nz || top.exact || top.distance > maxDistance) return null
        val runnerUp = ranked.firstOrNull { it.name != top.name }?.distance ?: 1.0
        return top.takeIf { runnerUp - top.distance >= margin }
    }

    private fun windows(heard: String): List<Window> {
        val words = heard.split(WHITESPACE).map { PlaceSpelling.fold(it) }.filter { it.isNotEmpty() }
        val out = ArrayList<Window>()
        for (start in words.indices) {
            if (words[start] in NON_PLACE_LEADING_WORDS) continue
            for (len in 1..MAX_WINDOW_WORDS) {
                if (start + len > words.size) break
                val folded = words.subList(start, start + len).joinToString("")
                out += Window(folded, PlaceSpelling.soundKey(folded))
            }
        }
        return out
    }

    private fun score(c: Candidate, windows: List<Window>): Scored {
        var best = 1.0
        var exact = false
        for (w in windows) {
            if (w.folded == c.folded) exact = true
            if (w.folded.length >= MIN_SPELLING_LETTERS && c.folded.length >= MIN_SPELLING_LETTERS) {
                best = minOf(best, normalised(w.folded, c.folded))
            }
            if (w.sound.length >= MIN_SOUND_KEY && c.sound.length >= MIN_SOUND_KEY) {
                best = minOf(best, normalised(w.sound, c.sound))
            }
        }
        return Scored(c.name, c.nz, if (exact) 0.0 else best, exact)
    }

    private fun normalised(a: String, b: String): Double =
        PlaceSpelling.distance(a, b).toDouble() / maxOf(a.length, b.length)

    private companion object {
        const val MAX_WINDOW_WORDS = 3
        const val MIN_SPELLING_LETTERS = 4
        const val MIN_SOUND_KEY = 3
        val WHITESPACE = Regex("\\s+")

        /** First words that make a phrase a description rather than a place name. */
        val NON_PLACE_LEADING_WORDS = setOf(
            "the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that",
            "to", "at", "in", "on", "from", "for", "of", "near", "home", "work",
        )
    }
}
