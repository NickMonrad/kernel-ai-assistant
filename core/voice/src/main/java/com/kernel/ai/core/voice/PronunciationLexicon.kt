package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Whole-word respellings applied to text before the Sherpa voices (Piper, Kokoro) speak it (#1852).
 *
 * Data: `core/voice/src/lexicon/assets/pronunciation_lexicon.json`. Format and how to add entries:
 * `docs/pronunciation-lexicon.md`.
 *
 * Matching is on NFC text with macrons folded and case ignored, so "Taupo", "Taupō" and a
 * decomposed "Tau\u0304po" all match the same entry. It is whole-word only (a letter or digit on
 * either side blocks a match) and leaves surrounding punctuation alone. All entries are matched in
 * one pass, longest word first. Entries with `verify: true` are applied too; the flag marks them
 * for owner review.
 */
internal object PronunciationLexicon {
    private const val TAG = "KernelAI"
    const val ASSET_NAME = "pronunciation_lexicon.json"

    data class Entry(val word: String, val say: String, val verify: Boolean)

    // Field names are read by Gson reflection. @SerializedName plus the keep rule in
    // core/voice/consumer-rules.pro stop R8 from renaming them in release builds.
    private data class RawFile(
        @SerializedName("entries") val entries: List<RawEntry>?,
    )

    private data class RawEntry(
        @SerializedName("word") val word: String?,
        @SerializedName("say") val say: String?,
        @SerializedName("verify") val verify: Boolean?,
    )

    /** Combined pattern over folded words (longest first) and folded-word → say lookup. */
    private data class Table(val pattern: Pattern?, val says: Map<String, String>)

    private val EMPTY = Table(pattern = null, says = emptyMap())

    private val MACRON_BASE: Map<Char, Char> = mapOf(
        'ā' to 'a', 'ē' to 'e', 'ī' to 'i', 'ō' to 'o', 'ū' to 'u',
        'Ā' to 'a', 'Ē' to 'e', 'Ī' to 'i', 'Ō' to 'o', 'Ū' to 'u',
    )

    @Volatile private var table: Table = EMPTY
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

    /** Replaces the active entries. Used by [ensureLoaded] and tests. First duplicate word wins. */
    internal fun install(entries: List<Entry>) {
        if (entries.isEmpty()) {
            table = EMPTY
            loaded = true
            return
        }
        val says = LinkedHashMap<String, String>()
        for (entry in entries) {
            says.putIfAbsent(fold(entry.word), entry.say)
        }
        val alternation = says.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Pattern.quote(it) }
        val pattern = Pattern.compile("(?<![\\p{L}\\p{N}])(?:$alternation)(?![\\p{L}\\p{N}])")
        table = Table(pattern, says)
        loaded = true
    }

    /**
     * NFC-normalises, then folds macrons and case one char at a time. The result has the same
     * length and indices as the NFC input, so matches on folded text map straight back onto it.
     */
    internal fun fold(s: String): String {
        val nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
        val out = CharArray(nfc.length)
        for (i in nfc.indices) {
            out[i] = Character.toLowerCase(MACRON_BASE[nfc[i]] ?: nfc[i])
        }
        return String(out)
    }

    fun apply(text: String): String {
        val current = table
        val pattern = current.pattern ?: return text
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val matcher = pattern.matcher(fold(nfc))
        val out = StringBuilder()
        var last = 0
        while (matcher.find()) {
            out.append(nfc, last, matcher.start())
            out.append(current.says.getValue(matcher.group()))
            last = matcher.end()
        }
        out.append(nfc, last, nfc.length)
        return out.toString()
    }
}
