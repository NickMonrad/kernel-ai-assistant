package com.kernel.ai.core.memory.profile

import android.util.Log
import com.kernel.ai.core.inference.InferenceEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2b (#374): Extract structured user profile fields from free-text using the on-device LLM.
 *
 * Uses [InferenceEngine.generateOnce] — an isolated conversation that does not affect
 * the active chat KV cache. Falls back gracefully (returns null) if the engine is not
 * ready, so [UserProfileRepository] can fall back to the heuristic regex parser.
 *
 * The extracted [UserProfileYaml] is logged to logcat (tag KernelAI) at DEBUG level
 * so device testing can validate the parsed output via `adb logcat -s KernelAI`.
 */
@Singleton
class UserProfileExtractionUseCase @Inject constructor(
    private val inferenceEngine: InferenceEngine,
) {
    companion object {
        private const val TAG = "KernelAI"

        private val SYSTEM_PROMPT = """
You are a structured data extractor. Extract user profile information from the input text.

Return ONLY a JSON object using these fields (omit any field that is absent or unknown):
  "name"        – the person's given name or full name (string)
  "role"        – their job title or occupation (string, e.g. "software developer")
  "location"    – their city and/or country (string, e.g. "Brisbane, Australia")
  "environment" – tools, devices, OS, apps, or infrastructure they use (array of strings, one item per tool/system)
  "context"     – hobbies, interests, family, or background facts (array of strings, one item per fact)
  "rules"       – preferences, tone instructions, or directives for the AI assistant (array of strings, one item per rule)

Rules for extraction:
- Each array item must be a short, standalone fact (one sentence or phrase maximum)
- Do not invent information not present in the input
- Do not include markdown, explanation, or code fences — return only the JSON object
- If no information is available for a field, omit it entirely
        """.trimIndent()

        // Strip markdown code fences the model may produce despite instructions
        private val CODE_FENCE = Regex("""^```(?:json)?\s*|\s*```$""")
    }

    /**
     * Extract structured profile from [freeText] using the on-device LLM.
     *
     * @return Parsed [UserProfileYaml], or null if the engine is not ready or extraction fails.
     */
    suspend fun extract(freeText: String): UserProfileYaml? {
        if (!inferenceEngine.isReady.value) {
            Log.d(TAG, "Profile LLM extraction skipped — engine not ready")
            return null
        }
        return try {
            val raw = inferenceEngine.generateOnce(freeText, SYSTEM_PROMPT)
            if (raw.isBlank()) {
                Log.w(TAG, "Profile LLM extraction returned blank response")
                return null
            }
            val json = CODE_FENCE.replace(raw.trim(), "").trim()
            val result = UserProfileYaml.fromJson(json)
            if (result != null) {
                Log.d(TAG, "Profile LLM extraction succeeded:\n${result.toYaml()}")
            } else {
                Log.w(TAG, "Profile LLM extraction: JSON parse failed. Raw response:\n$raw")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Profile LLM extraction failed: ${e.message}")
            null
        }
    }
}
