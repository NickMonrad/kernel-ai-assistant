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
private const val KEY_TRUTHS_SEEDED = "truths_seeded"
private const val SESSION_VOCAB_COUNT = 4

/**
 * Provides Jandal's dynamic personality elements: time-aware greetings, a randomised
 * session vocab drawn from [jandal_vocab.json], and Kiwi truths loaded from
 * [jandal_truths.json] for one-time core-memory seeding on first launch.
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

    /** Full list of Kiwi truths loaded from jandal_truths.json. */
    val truths: List<String> by lazy { loadTruths() }

    /** Vocab pool loaded from jandal_vocab.json. */
    private val vocabPool: List<VocabEntry> by lazy { loadVocab() }

    /** True once truths have been seeded into core memory — prevents re-seeding. */
    val isTruthsSeeded: Boolean get() = prefs.getBoolean(KEY_TRUTHS_SEEDED, false)

    /** Call after seeding to mark as done. */
    fun markTruthsSeeded() {
        prefs.edit().putBoolean(KEY_TRUTHS_SEEDED, true).commit() // synchronous — must be persisted before mutex releases
        Log.i(TAG, "Kiwi truths marked as seeded")
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
     * Picks [SESSION_VOCAB_COUNT] random entries from the vocab pool and returns a
     * compact prompt hint so the model can weave them in naturally.
     * Returns empty string if the vocab file failed to load.
     */
    fun buildSessionVocab(): String {
        if (vocabPool.isEmpty()) return ""
        val picked = vocabPool.shuffled().take(SESSION_VOCAB_COUNT)
        val entries = picked.joinToString(", ") { "\"${it.phrase}\" (${it.meaning})" }
        return "You may naturally use some of these Kiwi expressions where they fit: $entries."
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun loadTruths(): List<String> = try {
        val json = context.assets.open("jandal_truths.json").bufferedReader().readText()
        val arr = JSONArray(json)
        List(arr.length()) { arr.getString(it) }.also {
            Log.d(TAG, "Loaded ${it.size} Kiwi truths")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load jandal_truths.json", e)
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

    private data class VocabEntry(val phrase: String, val meaning: String)
}
