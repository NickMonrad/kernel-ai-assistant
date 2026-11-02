package com.kernel.ai.debug.llamaprobe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug-only LFM3-1.7B llama.cpp CPU smoke (issue #702).
 *
 * Invoke with `-e lfm3Smoke true`. The host stages the GGUF at `files/lfm3/LFM3-1.7B-QAD-Q4_0.gguf`
 * via run-as. Results go to `files/lfm3/smoke_results.json`, pulled via run-as. Logcat carries
 * metric rows only; prompt and generated text never reach logcat.
 */
@RunWith(AndroidJUnit4::class)
class LlamaLfm3SmokeTest {
    @Test
    fun lfm3CpuSmoke() = runBlocking {
        assumeTrue(
            "invoke only with -e lfm3Smoke true",
            InstrumentationRegistry.getArguments().getString("lfm3Smoke") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "lfm3/$MODEL_FILE")
        require(modelFile.isFile) { "LFM3 GGUF is missing at ${modelFile.path}" }
        val resultFile = File(context.filesDir, "lfm3/smoke_results.json")

        val perf = JSONArray()
        val quality = JSONArray()
        val report = JSONObject()
            .put("model_file", MODEL_FILE)
            .put("context_tokens", CONTEXT_TOKENS)
            .put(
                "sampling",
                JSONObject().put("temp", 0.1).put("top_k", 50).put("repeat_penalty", 1.1).put("repeat_last_n", 64),
            )
            .put("perf_max_tokens", PERF_MAX_TOKENS)
            .put("quality_max_tokens", QUALITY_MAX_TOKENS)
            .put("perf", perf)
            .put("quality", quality)
        fun flush() {
            resultFile.writeText(report.toString(2))
        }

        val session = LlamaCppSession.load(modelFile, contextSize = CONTEXT_TOKENS)
        try {
            report.put("cold_load_ms", session.loadMs).put("rss_kb_after_load", vmHwmKb())
            flush()
            Log.i(TAG, "lfm3_load load_ms=${session.loadMs} rss_kb=${vmHwmKb()}")

            for (thinking in listOf(false, true)) {
                var ttft8kMs = Long.MAX_VALUE
                for (target in listOf(SHORT_PROMPT, 4096, 8192)) {
                    val row = perfRun(session, target, thinking)
                    perf.put(row)
                    flush()
                    Log.i(TAG, "lfm3_perf $row")
                    if (target == 8192) ttft8kMs = row.getLong("ttft_ms")
                }
                // 16K only when the 8K TTFT for this mode is under the gate (brief: about 20 s).
                if (ttft8kMs < TTFT_GATE_MS) {
                    val row = perfRun(session, 16_000, thinking)
                    perf.put(row)
                    flush()
                    Log.i(TAG, "lfm3_perf $row")
                } else {
                    report.put("skipped_16k_thinking_$thinking", "8k ttft_ms=$ttft8kMs >= $TTFT_GATE_MS")
                    flush()
                }
            }

            for (question in QUALITY_QUESTIONS) {
                for (thinking in listOf(false, true)) {
                    val generation = session.run(question.system, question.user, thinking, QUALITY_MAX_TOKENS)
                    val answer = if (thinking) generation.text.substringAfterLast("</think>", "") else generation.text
                    val truncatedThinking = thinking && !generation.text.contains("</think>")
                    val passed = !truncatedThinking && Regex(question.expect, RegexOption.IGNORE_CASE).containsMatchIn(answer)
                    quality.put(
                        JSONObject()
                            .put("id", question.id)
                            .put("source", question.source)
                            .put("thinking", thinking)
                            .put("passed", passed)
                            .put("complete", generation.complete)
                            .put("prompt_tokens", generation.promptTokens)
                            .put("generated_tokens", generation.generatedTokens)
                            .put("text", generation.text),
                    )
                    flush()
                    Log.i(TAG, "lfm3_quality id=${question.id} thinking=$thinking passed=$passed complete=${generation.complete}")
                }
            }
            report.put("rss_kb_peak_final", vmHwmKb())
            flush()
        } finally {
            session.close()
        }
    }

    private suspend fun perfRun(session: LlamaCppSession, target: Int, thinking: Boolean): JSONObject {
        val system = if (target == SHORT_PROMPT) SHORT_SYSTEM else JANDAL_SYSTEM
        val user = if (target == SHORT_PROMPT) SHORT_USER else shapedUser(target)
        val generation = session.run(system, user, thinking, PERF_MAX_TOKENS)
        return JSONObject()
            .put("target_prompt_tokens", target)
            .put("thinking", thinking)
            .put("prompt_tokens", generation.promptTokens)
            .put("generated_tokens", generation.generatedTokens)
            .put("complete", generation.complete)
            .put("ttft_ms", generation.timeToFirstTokenMs)
            .put("decode_ms", generation.decodeMs)
            .put("decode_tok_s", generation.decodeTokensPerSecond)
            .put("total_ms", generation.totalMs)
            .put("rss_kb", vmHwmKb())
    }

    private suspend fun LlamaCppSession.run(
        system: String,
        user: String,
        thinking: Boolean,
        maxTokens: Int,
    ): LlamaGeneration = withTimeout(RUN_TIMEOUT_MS) {
        generate(
            prompt = user,
            systemPrompt = system,
            schemaJson = "",
            maxTokens = maxTokens,
            enableThinking = thinking,
            requireEndOfGeneration = false,
        )
    }

    /** Jandal-shaped user turn: a long numeric meal/budget log followed by a short question. */
    private fun shapedUser(targetTokens: Int): String {
        val approxChars = targetTokens * CHARS_PER_TOKEN_ESTIMATE
        val log = StringBuilder("Meal and budget log export. Each line is one entry.\n")
        var i = 0
        while (log.length < approxChars) {
            i++
            log.append(
                "2026-10-%02d breakfast 80 g oats, 250 ml milk, 1 banana; lunch 150 g chicken, 200 g rice; dinner 120 g salmon; spent \$%d.%02d\n"
                    .format(i % 28 + 1, 4 + i % 9, i % 100),
            )
        }
        log.append("\nSummarise the log in one sentence.")
        return log.toString()
    }

    private fun vmHwmKb(): Long =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: -1L

    private data class QualityQuestion(
        val id: String,
        val system: String,
        val user: String,
        val expect: String,
        val source: String,
    )

    private companion object {
        const val TAG = "LlamaLfm3Smoke"
        const val MODEL_FILE = "LFM3-1.7B-QAD-Q4_0.gguf"
        const val CONTEXT_TOKENS = 16384
        const val SHORT_PROMPT = 16
        const val PERF_MAX_TOKENS = 128
        const val QUALITY_MAX_TOKENS = 512
        const val RUN_TIMEOUT_MS = 20 * 60_000L
        const val TTFT_GATE_MS = 20_000L
        const val CHARS_PER_TOKEN_ESTIMATE = 3

        const val SHORT_SYSTEM = "You are Jandal, a helpful on-device assistant."
        const val SHORT_USER = "What is 17 + 25? Answer briefly."
        const val JANDAL_SYSTEM = """You are Jandal, a local on-device assistant. Use only the tools listed.
Tools (JSON):
{"name":"log_meal","parameters":{"type":"object","properties":{"item":{"type":"string"},"grams":{"type":"number"}},"required":["item","grams"]}}
{"name":"recall_memory","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}
{"name":"set_reminder","parameters":{"type":"object","properties":{"text":{"type":"string"},"at_iso":{"type":"string"}},"required":["text","at_iso"]}}
Keep numbers exactly as written by the user. Do not round, expand, or drop digits."""

        const val LITERT_SYSTEM = "You are a helpful assistant."
        const val NUMERIC_SYSTEM = "You are Jandal, a local on-device assistant. Keep numbers exactly as written."

        /**
         * litert-community check questions: the converter repo's verify_quality.py list, with its
         * " Answer briefly." suffix and expected-answer regexes. Numeric questions are derived from the
         * #968/#385 failure shapes, not verbatim user prompts.
         */
        val QUALITY_QUESTIONS = listOf(
            QualityQuestion("litert-1", LITERT_SYSTEM, "What is 17 + 25? Answer briefly.", "\\b42\\b", "litert-verify_quality"),
            QualityQuestion("litert-2", LITERT_SYSTEM, "What is the capital of Japan? Answer briefly.", "tokyo", "litert-verify_quality"),
            QualityQuestion("litert-3", LITERT_SYSTEM, "What is the opposite of \"hot\"? Answer briefly.", "\\bcold\\b", "litert-verify_quality"),
            QualityQuestion("litert-4", LITERT_SYSTEM, "How many days are in a week? Answer briefly.", "\\bseven\\b|\\b7\\b", "litert-verify_quality"),
            QualityQuestion("litert-5", LITERT_SYSTEM, "How do you say \"thank you\" in French? Answer briefly.", "merci", "litert-verify_quality"),
            QualityQuestion("litert-6", LITERT_SYSTEM, "What is 8 times 7? Answer briefly.", "\\b56\\b", "litert-verify_quality"),
            QualityQuestion("litert-7", LITERT_SYSTEM, "Which is larger: 0.9 or 0.11? Answer briefly.", "0\\.9", "litert-verify_quality"),
            QualityQuestion("litert-8", LITERT_SYSTEM, "Complete the rhyme: \"Roses are red, violets are ___\" Answer briefly.", "\\bblue\\b", "litert-verify_quality"),
            QualityQuestion("jandal-num-1", NUMERIC_SYSTEM, "Remember this number for later: 1475. What number did I ask you to remember? Answer with the number only.", "1475", "derived #968 remember 1475"),
            QualityQuestion("jandal-num-2", NUMERIC_SYSTEM, "The latest report says the completion rate is 92%. What is the completion rate, exactly as written?", "92%", "derived #968 completion rate fixture"),
            QualityQuestion("jandal-num-3", NUMERIC_SYSTEM, "My battery is at 76%. What percentage is my battery at? Answer with the number and % only.", "76%", "derived #385 battery 76%"),
            QualityQuestion("jandal-num-4", NUMERIC_SYSTEM, "The ledger has entries for 2007 and 2009. Which two years are listed? Answer with the years only.", "(?=.*2007)(?=.*2009)(?!.*200007)(?!.*\\b209\\b)", "derived #968 2007/2009 corruption"),
            QualityQuestion("jandal-num-5", NUMERIC_SYSTEM, "Subtract 16.6 from 100.0 and state the result as a number.", "83\\.4", "derived #968 decimal case"),
        )
    }
}
