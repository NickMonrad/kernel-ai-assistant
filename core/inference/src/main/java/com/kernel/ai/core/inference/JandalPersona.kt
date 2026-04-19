package com.kernel.ai.core.inference

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "JandalPersona"
private const val PREFS_NAME = "jandal_persona"
private const val KEY_TRUTHS_SEEDED = "truths_seeded_v3"  // bumped: corrected Ghost Chips + Monique entries
private const val KEY_LAST_VOCAB_INDICES = "last_vocab_indices"
private const val SESSION_VOCAB_COUNT = 2

/**
 * Provides Jandal's dynamic personality elements: time-aware greetings, a randomised
 * session vocab drawn from [jandal_vocab.json], and NZ truth memories loaded from
 * [nz_truth_memories.json] for one-time core-memory seeding on first launch.
 *
 * Asset files live in `core/inference/src/main/assets/` and can be updated without
 * touching logic code — just edit the JSON and ship an update.
 */
@Singleton
class JandalPersona @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Structured NZ truth entries loaded from nz_truth_memories.json. */
    val nzTruths: List<NzTruthEntry> by lazy { loadNzTruths() }

    /** Vocab pool loaded from jandal_vocab.json. */
    private val vocabPool: List<VocabEntry> by lazy { loadVocab() }

    /** True once truths have been seeded into core memory — prevents re-seeding. */
    val isTruthsSeeded: Boolean get() = prefs.getBoolean(KEY_TRUTHS_SEEDED, false)

    /** Call after seeding to mark as done. */
    fun markTruthsSeeded() {
        prefs.edit().putBoolean(KEY_TRUTHS_SEEDED, true).commit() // synchronous — must be persisted before mutex releases
        Log.i(TAG, "NZ truth memories marked as seeded")
    }

    /** Reset the seeded flag — called when the DB is wiped so truths are re-seeded on next launch. */
    fun resetTruthsSeeded() {
        prefs.edit().putBoolean(KEY_TRUTHS_SEEDED, false).apply()
        Log.i(TAG, "NZ truth memories seeded flag reset")
    }

    /**
     * Returns an explicit time-aware greeting instruction tailored to whether this is the
     * first reply in the conversation or a follow-up.
     *
     * @param isFirstReply true when there is no prior conversation history (i.e. turn 1).
     *
     * - First reply + morning (05:00–11:59) → open with Morena
     * - First reply + other hours → open with Kia ora
     * - Follow-up reply → explicitly forbid any greeting opener
     */
    fun buildGreetingInstruction(isFirstReply: Boolean): String {
        if (!isFirstReply) {
            return "Do NOT start this reply with a greeting — you already greeted the user earlier in the conversation. " +
                "Use the user's name occasionally for warmth, not as a prefix on every response."
        }
        val hour = LocalTime.now().hour
        val greetWord = if (hour in 5..11) {
            "Open your reply with 'Morena' (good morning in Māori)."
        } else {
            "Do not say 'Morena' — it is not morning. Open your reply with 'Kia ora'."
        }
        return "$greetWord Use the user's name occasionally for warmth, not as a prefix on every response."
    }

    /**
     * Picks [SESSION_VOCAB_COUNT] entries from the vocab pool with LRU cooldown
     * (avoids repeating the same phrases across sessions) and returns a compact
     * prompt hint so the model can weave them in naturally.
     * Returns empty string if the vocab file failed to load.
     */
    fun buildSessionVocab(): String {
        if (vocabPool.isEmpty()) return ""
        val lastUsed = prefs.getString(KEY_LAST_VOCAB_INDICES, "")
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet() ?: emptySet()
        // Prefer indices not recently used; fall back to full pool if all were used
        val candidates = vocabPool.indices.filter { it !in lastUsed }.ifEmpty { vocabPool.indices.toList() }
        val picked = candidates.shuffled().take(SESSION_VOCAB_COUNT)
        prefs.edit().putString(KEY_LAST_VOCAB_INDICES, picked.joinToString(",")).apply()
        val entries = picked.map { vocabPool[it] }.joinToString(", ") { "\"${it.phrase}\" (${it.meaning})" }
        return "You may naturally use some of these Kiwi expressions where they fit: $entries."
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun loadNzTruths(): List<NzTruthEntry> = try {
        val json = context.assets.open("nz_truth_memories.json").bufferedReader().readText()
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            val meta = obj.optJSONObject("metadata")
            NzTruthEntry(
                id = obj.getString("id"),
                term = obj.getString("term"),
                category = obj.getString("category"),
                definition = obj.getString("definition"),
                triggerContext = obj.optString("trigger_context", ""),
                vibeLevel = obj.optInt("vibe_level", 1),
                vectorText = obj.optString("vector_text", obj.getString("definition")),
                metadataJson = meta?.toString() ?: "{}",
            )
        }.also {
            Log.d(TAG, "Loaded ${it.size} NZ truth entries")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load nz_truth_memories.json", e)
        emptyList()
    }

    private fun loadVocab(): List<VocabEntry> = try {
        val json = context.assets.open("jandal_vocab.json").bufferedReader().readText()
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val obj = arr.getJSONObject(i)
            VocabEntry(phrase = obj.getString("phrase"), meaning = obj.getString("meaning"))
        }.also {
            Log.d(TAG, "Loaded ${it.size} vocab entries")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load jandal_vocab.json", e)
        emptyList()
    }

    data class NzTruthEntry(
        val id: String,
        val term: String,
        val category: String,
        val definition: String,
        val triggerContext: String,
        val vibeLevel: Int,
        /** Dense keyword string embedded into the vector store (not definition). */
        val vectorText: String,
        val metadataJson: String,
    )

    private data class VocabEntry(val phrase: String, val meaning: String)
}
