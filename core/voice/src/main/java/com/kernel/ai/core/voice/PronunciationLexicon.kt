package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.util.regex.Pattern

/**
 * Whole-word respellings applied to text before the Sherpa voices (Piper, Kokoro) speak it (#1852).
 *
 * Data: `core/voice/src/main/assets/pronunciation_lexicon.json`. Format and how to add entries:
 * `docs/pronunciation-lexicon.md`.
 *
 * Matching is case-insensitive, on whole words only (a letter or digit on either side blocks a
 * match, so Māori macrons count as letters), and leaves surrounding punctuation alone.
 * Entries with `verify: true` are applied too; the flag marks them for owner review.
 */
internal object PronunciationLexicon {
    private const val TAG = "KernelAI"
    const val ASSET_NAME = "pronunciation_lexicon.json"

    data class Entry(val word: String, val say: String, val verify: Boolean)

    private data class RawFile(val entries: List<RawEntry>?)
    private data class RawEntry(val word: String?, val say: String?, val verify: Boolean?)

    private data class Rule(val pattern: Regex, val say: String)

    @Volatile private var rules: List<Rule> = emptyList()
    @Volatile private var loaded = false

    /** Parses the JSON file. Throws [IllegalArgumentException] for a malformed entry. */
    fun parse(json: String): List<Entry> {
        val file = Gson().fromJson(json, RawFile::class.java)
            ?: throw IllegalArgumentException("empty lexicon file")
        return (file.entries ?: emptyList()).map { raw ->
            val word = raw.word?.trim().orEmpty()
            val say = raw.say?.trim().orEmpty()
            require(word.isNotEmpty() && say.isNotEmpty()) { "lexicon entry needs non-blank word and say" }
            Entry(word = word, say = say, verify = raw.verify ?: false)
        }
    }

    /** Loads the bundled asset once. A missing or malformed file leaves the lexicon empty. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                install(parse(json))
            } catch (e: Exception) {
                Log.w(TAG, "PronunciationLexicon: could not load $ASSET_NAME; speaking words as written", e)
                install(emptyList())
            }
        }
    }

    /** Replaces the active entries. Used by [ensureLoaded] and tests. */
    internal fun install(entries: List<Entry>) {
        rules = entries.map { entry ->
            val pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(entry.word) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
            Rule(Regex(pattern.pattern(), RegexOption.IGNORE_CASE), entry.say)
        }
        loaded = true
    }

    fun apply(text: String): String {
        var out = text
        for (rule in rules) {
            out = rule.pattern.replace(out) { rule.say }
        }
        return out
    }
}
