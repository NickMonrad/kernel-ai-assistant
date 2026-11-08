package com.kernel.ai.core.skills.eval

import com.kernel.ai.core.skills.intent.IntentContract
import com.kernel.ai.core.skills.intent.IntentRiskLevel
import com.kernel.ai.core.skills.slot.SlotSpec
import org.junit.jupiter.api.Assertions
import java.io.File

/**
 * Shared types for eval fixtures and scorecards.
 */

data class EvalFixture(
    val input: String,
    val candidateIntent: String,
    val confidence: Float,
    val precomputedSlots: Map<String, String>?,
    val expectedSlots: Map<String, String>,
    val expectedPolicy: ExpectedPolicy,
    val risk: String,
    val missingSlot: String?,
    val evalLayers: List<Int>,
)

data class ExpectedPolicy(
    val decision: String,
    val requiredSlotsPresent: Boolean,
    val confirmationRequired: Boolean,
    val allowAutoExecute: Boolean,
)

data class Scorecard(
    val totalFixtures: Int,
    val passedFixtures: Int,
    val failedFixtures: Int,
    val scoresByCategory: Map<String, ScoreDetail> = emptyMap(),
) {
    val passRate: Float get() = if (totalFixtures > 0) passedFixtures.toFloat() / totalFixtures else 1f
}

data class ScoreDetail(
    val total: Int,
    val passed: Int,
    val label: String,
) {
    val rate: Float get() = if (total > 0) passed.toFloat() / total else 1f
}

/**
 * Base class for intent recovery eval suites (layers 1-3).
 *
 * Eval suites load fixtures from the shared golden corpus at
 * `scripts/testdata/intent_recovery/recovery_corpus.json` and score each layer
 * independently, outputting a scorecard for PR reviewers.
 */
abstract class RecoveryEvalBase {

    /** Build a dummy intent contract for the given intent name and risk level. */
    protected fun dummyContract(
        intentName: String,
        risk: String,
        requiredSlots: Map<String, String> = emptyMap(),
    ): IntentContract = IntentContract(
        intentName = intentName,
        capability = "",
        requiredSlots = requiredSlots.mapValues { (_, prompt) ->
            SlotSpec(name = prompt, promptTemplate = "Enter $prompt")
        },
        riskLevel = when (risk) {
            "LOW" -> IntentRiskLevel.LOW
            "MEDIUM" -> IntentRiskLevel.MEDIUM
            else -> IntentRiskLevel.LOW
        },
    )

    protected fun findCorpusFile(): File {
        val cwd = File(System.getProperty("user.dir")!!)
        // Canonical location: core/skills/src/test/resources/ from module working dir
        val path = cwd.resolve("src/test/resources/recovery_corpus.json")
        return path.takeIf { it.exists() }
            ?: error("Cannot find recovery_corpus.json from ${cwd.absolutePath}")
    }
    /** Load fixtures from the golden corpus JSON file. */
    protected fun loadFixtures(): List<EvalFixture> {
        val file = findCorpusFile()
        val json = file.readText()
        return parseFixtures(json)
    }

    private fun parseFixtures(json: String): List<EvalFixture> {
        val trimmed = json.trim()
        Assertions.assertTrue(trimmed.startsWith("["), "Corpus must be a JSON array")

        val fixtures = mutableListOf<EvalFixture>()
        var depth = 0
        var objStart = -1
        var i = 0
        while (i < trimmed.length) {
            when (trimmed[i]) {
                '{' -> { if (depth++ == 0) objStart = i }
                '}' -> {
                    if (--depth == 0 && objStart >= 0) {
                        val obj = trimmed.substring(objStart, i + 1)
                        fixtures.add(parseFixture(obj))
                        objStart = -1
                    }
                }
                else -> {}
            }
            i++
        }
        return fixtures
    }

    private fun parseFixture(json: String): EvalFixture {
        fun extractStr(key: String): String? {
            val q = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            q.find(json)?.let { return it.groupValues[1] }
            val n = Regex("\"$key\"\\s*:\\s*([0-9.]+)")
            return n.find(json)?.groupValues?.get(1)
        }
        fun extractList(key: String): List<String> {
            val m = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]").find(json)?.groupValues?.get(1) ?: return emptyList()
            // Match quoted strings OR bare numbers
            val result = mutableListOf<String>()
            Regex("\"([a-zA-Z_0-9]+)\"").findAll(m).forEach { result.add(it.groupValues[1]) }
            Regex("\\b(\\d+)\\b").findAll(m).forEach { result.add(it.groupValues[1]) }
            return result
        }
        fun extractObj(key: String): String? {
            val start = Regex("\"$key\"\\s*:\\s*\\{").find(json)?.range?.last?.plus(1) ?: return null
            var d = 1; var pos = start
            while (pos < json.length && d > 0) {
                when (json[pos]) { '{' -> d++; '}' -> d-- }
                pos++
            }
            return json.substring(start, pos - 1).trimEnd(',')
        }
        fun extractMap(obj: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            Regex("\"([a-zA-Z_]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(obj).forEach { m ->
                map[m.groupValues[1]] = m.groupValues[2]
            }
            // Handle boolean values (not quoted)
            Regex("\"([a-zA-Z_]+)\"\\s*:\\s*(true|false)").findAll(obj).forEach { m ->
                map[m.groupValues[1]] = m.groupValues[2]
            }
            return map
        }
        fun extractNullableMap(key: String): Map<String, String>? {
            val raw = extractObj(key) ?: return null
            if (raw.trim() == "null") return null
            return extractMap(raw)
        }

        val input = extractStr("input") ?: error("Missing input")
        val candidateIntent = extractStr("candidateIntent") ?: error("Missing candidateIntent")
        val confidence = extractStr("confidence")?.toFloatOrNull() ?: error("Missing confidence")
        val precomputedSlots = extractNullableMap("precomputedSlots")
        val expectedSlots = extractMap(extractObj("expectedSlots") ?: "{}")
        val risk = extractStr("risk") ?: error("Missing risk")
        val missingSlot = extractStr("missingSlot")

        val policyObj = extractObj("expectedPolicy") ?: error("Missing expectedPolicy")
        val policyMap = extractMap(policyObj)
        val expectedPolicy = ExpectedPolicy(
            decision = policyMap["decision"] ?: error("Missing expectedPolicy.decision"),
            requiredSlotsPresent = policyMap["requiredSlotsPresent"]?.toBooleanStrictOrNull() ?: false,
            confirmationRequired = policyMap["confirmationRequired"]?.toBooleanStrictOrNull() ?: false,
            allowAutoExecute = policyMap["allowAutoExecute"]?.toBooleanStrictOrNull() ?: false,
        )

        val evalLayers = extractList("evalLayers").mapNotNull {
            when (it) { "1" -> 1; "2" -> 2; "3" -> 3; "4" -> 4; else -> null }
        }.ifEmpty { listOf(1, 2, 3) }

        return EvalFixture(
            input = input, candidateIntent = candidateIntent, confidence = confidence,
            precomputedSlots = precomputedSlots, expectedSlots = expectedSlots,
            expectedPolicy = expectedPolicy, risk = risk, missingSlot = missingSlot,
            evalLayers = evalLayers,
        )
    }

    /** Print the scorecard to stdout for CI visibility. */
    protected fun printScorecard(scorecard: Scorecard) {
        println("\n========== EVAL SCORECARD ==========")
        println("Total fixtures:  ${scorecard.totalFixtures}")
        println("Passed:          ${scorecard.passedFixtures}")
        println("Failed:          ${scorecard.failedFixtures}")
        println("Pass rate:       ${"%.1f".format(scorecard.passRate * 100)}%")
        if (scorecard.scoresByCategory.isNotEmpty()) {
            println()
            println("--- Layer scores ---")
            scorecard.scoresByCategory.entries.sortedBy { it.key }.forEach { (key, detail) ->
                println("${detail.label}: ${detail.passed}/${detail.total} (${"%.1f".format(detail.rate * 100)}%)")
            }
        }
        println("====================================\n")
    }
}
