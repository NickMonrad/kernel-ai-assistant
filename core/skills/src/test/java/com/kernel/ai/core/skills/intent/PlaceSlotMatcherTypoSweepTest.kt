package com.kernel.ai.core.skills.intent

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.text.Normalizer

/**
 * One-edit typo sweep over the NZ gazetteer (#2079). Every single-letter deletion, adjacent swap and
 * substitution of each NZ name is run through the production question path. Outcome per typo: the
 * right place prompted, a wrong place prompted, or no prompt. Uses only the bundled assets.
 *
 * Skipped unless PLACE_DYM_TYPO_SWEEP=1. Writes aggregates to PLACE_DYM_TYPO_OUT (default
 * build/place-dym-typo-sweep.txt).
 */
class PlaceSlotMatcherTypoSweepTest {

    private data class Typo(val kind: String, val text: String)

    @Test
    fun oneEditTyposOfNzNames() {
        assumeTrue(System.getenv("PLACE_DYM_TYPO_SWEEP") == "1")
        val out = File(System.getenv("PLACE_DYM_TYPO_OUT") ?: "build/place-dym-typo-sweep.txt")
        val nz = readAsset("nz_places.txt")
        val competitors = readAsset("competitor_places.txt")
        val suggester = PlaceDidYouMeanSuggester { PlaceSlotMatcher(nz, competitors) }

        // kind -> [total, right, wrong, none]
        val tally = sortedMapOf<String, IntArray>()
        for (target in nz) {
            for (typo in typos(target)) {
                val counts = tally.getOrPut(typo.kind) { IntArray(4) }
                counts[0]++
                when (suggester.suggest(typo.text)) {
                    null -> counts[3]++
                    target -> counts[1]++
                    else -> counts[2]++
                }
            }
        }
        val total = IntArray(4)
        for (c in tally.values) for (i in 0..3) total[i] += c[i]
        val report = StringBuilder()
        report.appendLine("nz=${nz.size} competitors=${competitors.size}")
        report.appendLine("typos=${total[0]} right=${total[1]} wrong=${total[2]} none=${total[3]}")
        for ((kind, c) in tally) {
            report.appendLine("$kind typos=${c[0]} right=${c[1]} wrong=${c[2]} none=${c[3]}")
        }
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
    }

    /** Distinct one-edit variants of [name] over its letters (spaces and punctuation kept). */
    private fun typos(name: String): List<Typo> {
        val base = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        val letters = base.indices.filter { base[it] in 'a'..'z' }
        val out = LinkedHashMap<String, Typo>()
        fun add(kind: String, text: String) {
            if (text.isNotBlank() && text != base) out.putIfAbsent(text, Typo(kind, text))
        }
        for (i in letters) {
            add("delete", base.removeRange(i, i + 1))
            for (c in 'a'..'z') if (c != base[i]) add("substitute", base.replaceRange(i, i + 1, c.toString()))
        }
        for (k in 0 until letters.size - 1) {
            val i = letters[k]
            val j = letters[k + 1]
            if (j == i + 1 && base[i] != base[j]) {
                val chars = base.toCharArray()
                chars[i] = base[j]
                chars[j] = base[i]
                add("swap", String(chars))
            }
        }
        return out.values.toList()
    }

    private fun readAsset(file: String): List<String> =
        File("src/main/assets/place_dym/$file").readLines().map { it.trim() }.filter { it.isNotEmpty() }
}
