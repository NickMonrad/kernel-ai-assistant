package com.kernel.ai.core.skills.eval

import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.CalendarSlotExtractor
import com.kernel.ai.core.skills.intent.IntentCandidate
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.RecoveryResult
import com.kernel.ai.core.skills.slot.SlotFillerManager
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase C eval: 512-utterance non-calendar false-positive sweep (#1103).
 *
 * Runs each utterance through [IntentRecoveryOrchestrator.recover()] and
 * classifies by actual policy outcome:
 * - Dangerous: RecoveryResult.Execute (auto-execute on non-calendar input)
 * - Visible: RecoveryResult.AskConfirmation (shows confirmation prompt)
 * - Benign: RecoveryResult.AskSlot/AskClarification/NotActionable
 *
 * Thresholds per #1103: dangerous FP = 0, visible FP <= 0.4%, total FP <= 1%.
 */
class FalsePositiveSweepEval : RecoveryEvalBase() {

    @Test
    fun `false positive sweep against non-calendar utterances`() {
        val registry = IntentContractRegistry()
        val slotFillerManager = mockk<SlotFillerManager>(relaxed = true)
        val skillRegistry = mockk<SkillRegistry>(relaxed = true)
        val orchestrator = IntentRecoveryOrchestrator(
            registry, slotFillerManager, skillRegistry, setOf(CalendarSlotExtractor())
        )

        val corpus = loadNonCalendarCorpus()
        val total = corpus.size
        var notActionable = 0
        var benign = 0
        var visible = 0
        var dangerous = 0

        for (entry in corpus) {
            val input = entry["input"] as String
            val candidate = IntentCandidate("create_calendar_event", 0.72f, "eval")
            val result = orchestrator.recover("fp-sweep", input, candidate)

            when (result) {
                is RecoveryResult.NotActionable -> notActionable++
                is RecoveryResult.AskSlot -> benign++
                is RecoveryResult.AskClarification -> benign++
                is RecoveryResult.AskConfirmation -> visible++
                is RecoveryResult.Execute -> dangerous++
            }
        }

        val visibleRate = visible.toDouble() / total * 100
        val totalFp = (visible + dangerous).toDouble()
        val totalFpRate = totalFp / total * 100

        println("\n========== FALSE POSITIVE SWEEP (#1103) ==========")
        println("Total utterances:    $total")
        println("NotActionable:       $notActionable")
        println("Benign (ask/clarify): $benign")
        println("Visible (confirm):   $visible (${"%.2f".format(visibleRate)}%)")
        println("Dangerous (execute): $dangerous")
        println()
        println("Visible FP rate:     ${"%.2f".format(visibleRate)}% (target <= 0.4%)")
        println("Total FP rate:       ${"%.2f".format(totalFpRate)}% (target <= 1%)")
        println("Dangerous FP:        $dangerous (target = 0)")
        println()

        if (dangerous > 0) {
            println("FAIL: Dangerous false positives detected")
            throw IllegalStateException("Dangerous FPs: $dangerous")
        }
        if (visibleRate > 0.4) {
            println("FAIL: Visible FP rate ${"%.2f".format(visibleRate)}% exceeds 0.4%")
            println()
            println("Note: Visible FPs are confirmation prompts shown for non-calendar input.")
            println("Reducing them below 0.4% requires classifier integration (beyond regex-only)")
            println("or more aggressive guard phrases in CalendarSlotExtractor.")
            throw IllegalStateException("Visible FP rate ${"%.2f".format(visibleRate)}% exceeds 0.4%")
        }
        if (totalFpRate > 1.0) {
            println("FAIL: Total FP rate ${"%.2f".format(totalFpRate)}% exceeds 1%")
            throw IllegalStateException("Total FP rate ${"%.2f".format(totalFpRate)}% exceeds 1%")
        }
        println("PASS: All thresholds met")
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadNonCalendarCorpus(): List<Map<String, Any?>> {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            cwd.resolve("src/test/resources/non_calendar_corpus.json"),
            cwd.resolve("../../../scripts/testdata/intent_recovery/non_calendar_corpus.json"),
            cwd.resolve("../../scripts/testdata/intent_recovery/non_calendar_corpus.json"),
            File("/home/lokhor/.omp/wt/epic-1099-orchestration-eval/scripts/testdata/intent_recovery/non_calendar_corpus.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Cannot find non_calendar_corpus.json from ${cwd.absolutePath}")
        val json = file.readText()
        val list = mutableListOf<Map<String, Any?>>()
        val trimmed = json.trim()
        var depth = 0
        var objStart = -1
        var i = if (trimmed.startsWith("[")) 1 else 0
        while (i < trimmed.length) {
            when (trimmed[i]) {
                '{' -> { if (depth++ == 0) objStart = i }
                '}' -> {
                    if (--depth == 0 && objStart >= 0) {
                        val obj = trimmed.substring(objStart, i + 1)
                        val map = mutableMapOf<String, Any?>()
                        Regex("\"([a-zA-Z_]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(obj).forEach { m ->
                            map[m.groupValues[1]] = m.groupValues[2]
                        }
                        list.add(map)
                        objStart = -1
                    }
                }
                else -> {}
            }
            i++
        }
        return list
    }
}
