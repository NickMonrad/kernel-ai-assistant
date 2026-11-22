package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-recognition glossary (#2063): known mis-hearings of NZ place names, Māori words and "Jandal",
 * corrected to the spelling Jandal uses. [SelectableVoiceInputController] applies it to every
 * transcript before routing and display, whichever engine produced it.
 *
 * Data: `core/voice/src/lexicon/assets/stt_glossary.json`. Each entry has the correct form and the wrong
 * forms observed from asr4all on the owner decision set and breadth clips. Only unambiguous wrong forms
 * belong here: a wrong form that is also a common English word is never listed.
 *
 * Matching follows [PronunciationLexicon]: NFC, macrons and case folded, so "Pukekohe", "Pūkekohe" and
 * "pūkekohe" match the same entry. It is whole-word (a letter or digit on either side blocks a match),
 * matches any run of whitespace between the words of a wrong form, and tries the longest wrong form
 * first. The pattern has no `(?U:...)` flag, which Android ICU rejects (#1968).
 */
@Singleton
class SttGlossary private constructor(private val table: Table) {

    @Inject constructor(@ApplicationContext context: Context) : this(load(context))

    /** Corrects every whole-word wrong form in [text]. Returns [text] unchanged when nothing matches. */
    fun apply(text: String): String {
        val pattern = table.pattern ?: return text
        if (text.isBlank()) return text
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val folded = PronunciationLexicon.fold(nfc)
        val matcher = pattern.matcher(folded)
        val out = StringBuilder()
        var last = 0
        while (matcher.find()) {
            out.append(nfc, last, matcher.start())
            out.append(table.correctFor.getValue(key(folded.substring(matcher.start(), matcher.end()))))
            last = matcher.end()
        }
        out.append(nfc, last, nfc.length)
        return out.toString()
    }

    internal class Table(val pattern: Pattern?, val correctFor: Map<String, String>)

    internal data class Entry(val correct: String, val wrong: List<String>)

    // Field names are read by Gson reflection. @SerializedName plus the keep rule in
    // core/voice/consumer-rules.pro stop R8 from renaming them in release builds.
    private data class RawFile(
        @SerializedName("entries") val entries: List<RawEntry>?,
    )

    private data class RawEntry(
        @SerializedName("correct") val correct: String?,
        @SerializedName("wrong") val wrong: List<String>?,
    )

    companion object {
        const val ASSET_NAME = "stt_glossary.json"
        private const val TAG = "SttGlossary"

        /** Any Unicode whitespace, written as an explicit class for Android ICU (#1968). */
        private const val WHITESPACE_PATTERN = "[\\s\\p{Z}]+"
        private val WHITESPACE = Regex(WHITESPACE_PATTERN)

        /** Builds a glossary from JSON text. Throws [IllegalArgumentException] for a malformed entry. */
        internal fun fromJson(json: String): SttGlossary = SttGlossary(build(parse(json)))

        /** Parses the JSON file. Throws [IllegalArgumentException] for a malformed entry. */
        internal fun parse(json: String): List<Entry> {
            val file = Gson().fromJson(json, RawFile::class.java)
                ?: throw IllegalArgumentException("empty glossary file")
            return (file.entries ?: emptyList()).map { raw ->
                val correct = raw.correct?.trim().orEmpty()
                val wrong = raw.wrong.orEmpty().map { it.trim() }
                require(correct.isNotEmpty() && wrong.isNotEmpty() && wrong.all { it.isNotEmpty() }) {
                    "glossary entry needs a non-blank correct form and non-blank wrong forms"
                }
                Entry(correct = correct, wrong = wrong)
            }
        }

        /** Loads the bundled asset. A missing or malformed file leaves the glossary empty. */
        private fun load(context: Context): Table =
            try {
                val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                build(parse(json))
            } catch (e: Exception) {
                Log.w(TAG, "SttGlossary: could not load $ASSET_NAME; transcripts are not corrected", e)
                build(emptyList())
            }

        internal fun build(entries: List<Entry>): Table {
            if (entries.isEmpty()) return Table(pattern = null, correctFor = emptyMap())
            val correctFor = LinkedHashMap<String, String>()
            for (entry in entries) {
                for (wrong in entry.wrong) correctFor.putIfAbsent(key(PronunciationLexicon.fold(wrong)), entry.correct)
            }
            val alternation = correctFor.keys
                .sortedByDescending { it.length }
                .joinToString("|") { k -> k.split(' ').joinToString(WHITESPACE_PATTERN) { Pattern.quote(it) } }
            val pattern = Pattern.compile("(?<![\\p{L}\\p{N}])(?:$alternation)(?![\\p{L}\\p{N}])")
            return Table(pattern, correctFor)
        }

        /** Lookup key: folded words joined by one space, so any whitespace between words finds the entry. */
        private fun key(folded: String): String = folded.trim().split(WHITESPACE).joinToString(" ")
    }
}
