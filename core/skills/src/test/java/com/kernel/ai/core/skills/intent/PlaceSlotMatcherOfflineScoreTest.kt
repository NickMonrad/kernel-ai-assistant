package com.kernel.ai.core.skills.intent

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Offline scoring of [PlaceSlotMatcher] over recorded owner lines (#2079 step 1). Skipped unless
 * PLACE_DYM_DIR is set. Reads private inputs from that directory and writes aggregates only to
 * PLACE_DYM_OUT. Input files: owner.tsv (id, size, class, gold, greedy text), nz.txt,
 * competitors-*.txt. Line texts never leave the private directory.
 */
class PlaceSlotMatcherOfflineScoreTest {

    private data class Row(val id: String, val size: String, val cls: String, val gold: String?, val heard: String)

    @Test
    fun scoreOwnerLines() {
        val dir = System.getenv("PLACE_DYM_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(System.getenv("PLACE_DYM_OUT") ?: "${dir!!.path}/results.txt")

        val rows = File(dir!!, "owner.tsv").readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.split("\t")
            Row(f[0], f[1], f[2], f[3].ifEmpty { null }, f[4])
        }
        val nz = File(dir, "nz.txt").readLines().filter { it.isNotBlank() }
        val impostors = File(dir, "impostors.txt").readLines().filter { it.isNotBlank() }
        val au = File(dir, "au_geonames.txt").readLines().filter { it.isNotBlank() }
        val linz = File(dir, "linz_locality_suburb.txt").readLines().filter { it.isNotBlank() }

        val variants = linkedMapOf(
            "V0 nz only" to PlaceSlotMatcher(nz),
            "V1 nz+impostors" to PlaceSlotMatcher(nz, impostors),
            "V2 nz+impostors+au" to PlaceSlotMatcher(nz, impostors + au),
            "V3 nz+impostors+au+linz" to PlaceSlotMatcher(nz, impostors + au + linz),
        )
        val thresholds = listOf(0.15, 0.2, 0.25, 0.3, 0.35, 0.4)
        val margins = listOf(0.0, 0.05, 0.1, 0.15, 0.2)
        val report = StringBuilder()

        fun slot(heard: String): String {
            val words = heard.split(Regex("\\s+"))
            val last = words.indexOfLast { it.lowercase().trim(',', '?', '.') in setOf("to", "in", "at", "for") }
            return if (last >= 0 && last < words.size - 1) words.drop(last + 1).joinToString(" ") else heard
        }

        for (size in listOf("M", "L")) {
            val sized = rows.filter { it.size == size }
            val gold = sized.filter { it.gold != null }
            val nonGold = sized.filter { it.gold == null }
            // Rank among NZ names only, independent of competitors.
            val nzMatcher = variants.getValue("V0 nz only")
            var top1 = 0; var top3 = 0
            for (r in gold) {
                val ranked = nzMatcher.rank(slot(r.heard)).filter { it.nz }.map { it.name }
                val pos = ranked.indexOf(r.gold)
                if (pos == 0) top1++
                if (pos in 0..2) top3++
            }
            report.appendLine("size=$size gold=${gold.size} nonGold=${nonGold.size}")
            report.appendLine("  gold rank among NZ: top1=$top1 top3=$top3")

            for ((vName, matcher) in variants) {
                for (t in thresholds) for (m in margins) {
                    var okPrompt = 0; var wrongPrompt = 0; var noPrompt = 0
                    for (r in gold) {
                        val s = matcher.suggest(slot(r.heard), t, m)
                        when {
                            s == null -> noPrompt++
                            s.name == r.gold -> okPrompt++
                            else -> wrongPrompt++
                        }
                    }
                    val fired = nonGold.filter { matcher.suggest(slot(it.heard), t, m) != null }
                    val byClass = fired.groupingBy { it.cls }.eachCount().toSortedMap()
                    report.appendLine(
                        "$vName t=$t m=$m | gold ok=$okPrompt wrong=$wrongPrompt none=$noPrompt /${gold.size} | " +
                            "nongold prompts=${fired.size} /${nonGold.size} ${byClass}"
                    )
                }
            }
        }
        out.writeText(report.toString())
    }
}
