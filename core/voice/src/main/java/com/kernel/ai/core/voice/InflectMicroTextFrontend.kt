package com.kernel.ai.core.voice

import java.text.Normalizer
import kotlin.math.abs

/**
 * Kotlin port of the pinned Inflect v2 reference frontend's deterministic text preparation.
 *
 * This intentionally stops before eSpeak: the custom Sherpa JNI seam supplies the reference
 * Piper/eSpeak phonemes, while this class owns all runtime text expansion and Inflect-specific
 * pronunciation corrections. Language-level numbers, money, dates and times come from [EnglishSpeechTextNormaliser].
 */
object InflectMicroTextFrontend {
    private val whitespace = Regex("\\s+")
    /** Sentences longer than this many phonemes, joined by " and ", are split at the join (see [phonemizeChunks]). */
    private const val CLAUSE_SPLIT_PHONEME_LENGTH = 100
    private val numberWordAfterAnd = Regex(
        "^and (?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
            "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|" +
            "billion|trillion)\\b",
    )

    private val abbreviations = listOf(
        "Dr." to "doctor", "Mr." to "mister", "Mrs." to "missus", "Ms." to "miss",
        "Prof." to "professor", "St." to "saint", "vs." to "versus", "etc." to "et cetera",
        "e.g." to "for example", "i.e." to "that is",
    )

    private val wordOverrides = listOf(
        "Qwen3" to "Qwen three", "Qwen" to "Qwen", "PyTorch" to "pie torch",
        "SQLite" to "ess cue lite", "USB-C" to "you ess bee see",
        "RTX 3060" to "ar tee ex thirty sixty", "RTX 3090" to "ar tee ex thirty ninety",
        "RTX 4090" to "ar tee ex forty ninety", "RTX 5080" to "ar tee ex fifty eighty",
        "RTX 5090" to "ar tee ex fifty ninety",
    )

    fun normalize(text: String): String {
        var value = text
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace("—", ", ")
            .replace("…", "...")
            .replace("(", ", ").replace(")", ", ")
            .replace("[", ", ").replace("]", ", ")
            .replace("{", ", ").replace("}", ", ")
            .replace(whitespace, " ")
            .trim()

        wordOverrides.forEach { (source, replacement) ->
            value = value.replace(Regex("\\b${Regex.escape(source)}\\b"), replacement)
        }
        abbreviations.forEach { (source, replacement) ->
            value = value.replace(Regex("\\b${Regex.escape(source)}", RegexOption.IGNORE_CASE), replacement)
        }

        value = value.replace(Regex("\\b([A-Z])(?:\\.([A-Z]))+\\.")) { match ->
            match.value.filter { it in 'A'..'Z' }.toCharArray().joinToString(" ")
        }
        value = value.replace(
            Regex("\\b(apartment|apt\\.?|suite|unit|room|flight|extension|order|invoice|locker|aisle|gate)\\s+([A-Za-z]?\\d{1,4}[A-Za-z]?)\\b", RegexOption.IGNORE_CASE),
        ) { match -> "${match.groupValues[1]} ${expandIdentifierToken(match.groupValues[2])}" }
        value = value.replace(
            Regex("\\b(\\d{3})(?=\\s+(?:North|South|East|West)\\b)", RegexOption.IGNORE_CASE),
        ) { match -> EnglishSpeechTextNormaliser.expandDigits(match.groupValues[1]) }
        value = EnglishSpeechTextNormaliser.normalize(value)
        value = value.replace(Regex("\\b[A-Z]{2,}\\b")) { match ->
            match.value.map { EnglishSpeechTextNormaliser.letterName(it) }.joinToString(" ")
        }

        value = value.replace(Regex(",(?:\\s*,)+"), ",")
            .replace(Regex(",\\s*([.!?])"), "$1")
            .replace(Regex("\\s+([,;:.!?])"), "$1")
            .replace(Regex("([,;:.!?])(?=\\S)"), "$1 ")
        return value.replace(whitespace, " ").trim()
    }

    fun applyPhonemeOverrides(phonemes: String): String {
        var value = Normalizer.normalize(phonemes, Normalizer.Form.NFD)
        value = value.replace("sˈæskɐtʃˌuːən", "sɐskˈætʃəwən")
        value = value.replace("flʊɹɹˈɛsənt", "flʊˈɹɛsənt")
        return value.replace(whitespace, " ").trim()
    }

    data class PhonemizedChunk(
        val text: String,
        val phonemes: String,
    )

    /**
     * Keeps normalized text as one chunk when it fits, then splits at punctuation boundaries and
     * at words only when one punctuation-delimited segment still exceeds the ONNX frontend bound.
     *
     * A text longer than [clauseSplitPhonemeLength] is first split at " and " joins between clauses, nearest the
     * middle first. Inflect garbled "fifteen thousand" in "The stadium holds fifteen thousand people and cost one
     * million …" (#2115) while the same phrase alone rendered correctly. Joins before a number word ("two thousand and
     * five") are kept.
     */
    fun phonemizeChunks(
        normalizedText: String,
        maxPhonemeLength: Int = InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH,
        clauseSplitPhonemeLength: Int = CLAUSE_SPLIT_PHONEME_LENGTH,
        phonemize: (String) -> String,
    ): List<PhonemizedChunk> {
        require(maxPhonemeLength > 0) { "maxPhonemeLength must be positive" }
        val text = normalizedText.trim()
        if (text.isEmpty()) return emptyList()

        fun phonemizeChecked(value: String): String =
            phonemize(value).also { require(it.isNotBlank()) { "Phonemizer returned empty output." } }

        val completePhonemes = phonemizeChecked(text)
        val clauses = if (completePhonemes.length > clauseSplitPhonemeLength) {
            splitAtAndClauses(text, clauseSplitPhonemeLength, ::phonemizeChecked)
        } else {
            listOf(text)
        }
        if (clauses.size == 1 && completePhonemes.length <= maxPhonemeLength) {
            return listOf(PhonemizedChunk(text, completePhonemes))
        }
        return clauses.flatMap { clause ->
            chunkClause(
                text = clause,
                maxPhonemeLength = maxPhonemeLength,
                phonemize = ::phonemizeChecked,
                knownPhonemes = completePhonemes.takeIf { clauses.size == 1 },
            )
        }
    }

    /** Splits at " and " joins that begin a clause, nearest the middle first, until each part fits or no join is left. */
    private fun splitAtAndClauses(text: String, softLength: Int, phonemize: (String) -> String): List<String> {
        if (phonemize(text).length <= softLength) return listOf(text)
        val middle = text.length / 2
        val join = Regex(" and ").findAll(text)
            .map { it.range.first }
            .filterNot { numberWordAfterAnd.containsMatchIn(text.substring(it + 1)) }
            .minByOrNull { abs(it - middle) }
            ?: return listOf(text)
        val before = text.substring(0, join).trimEnd(',').let { if (it.last() in ".!?;:") it else "$it." }
        val after = text.substring(join + 1)
        return splitAtAndClauses(before, softLength, phonemize) + splitAtAndClauses(after, softLength, phonemize)
    }

    private fun chunkClause(
        text: String,
        maxPhonemeLength: Int,
        phonemize: (String) -> String,
        knownPhonemes: String?,
    ): List<PhonemizedChunk> {
        val segments = text.split(Regex("(?<=[.!?;,:])\\s+"))
        val initialSegmentPhonemes = knownPhonemes.takeIf { segments.size == 1 }
        return segments
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { segment ->
                phonemizeSegment(
                    text = segment,
                    maxPhonemeLength = maxPhonemeLength,
                    phonemize = phonemize,
                    initialPhonemes = initialSegmentPhonemes,
                ).asSequence()
            }
            .toList()
    }

    private fun phonemizeSegment(
        text: String,
        maxPhonemeLength: Int,
        phonemize: (String) -> String,
        initialPhonemes: String? = null,
    ): List<PhonemizedChunk> {
        val phonemes = initialPhonemes ?: phonemize(text)
        if (phonemes.length <= maxPhonemeLength) {
            return listOf(PhonemizedChunk(text, phonemes))
        }

        val chunks = mutableListOf<PhonemizedChunk>()
        var currentText: String? = null
        var currentPhonemes: String? = null
        for (word in text.split(whitespace).filter(String::isNotEmpty)) {
            val candidateText = currentText?.let { "$it $word" } ?: word
            val candidatePhonemes = phonemize(candidateText)
            if (candidatePhonemes.length <= maxPhonemeLength) {
                currentText = candidateText
                currentPhonemes = candidatePhonemes
                continue
            }
            val completedText = currentText
            val completedPhonemes = currentPhonemes
            if (completedText != null && completedPhonemes != null) {
                chunks += PhonemizedChunk(completedText, completedPhonemes)
            }
            val wordPhonemes = phonemize(word)
            require(wordPhonemes.length <= maxPhonemeLength) {
                "Inflect word exceeds $maxPhonemeLength phoneme characters."
            }
            currentText = word
            currentPhonemes = wordPhonemes
        }
        currentText?.let { chunks += PhonemizedChunk(it, currentPhonemes!!) }
        return chunks
    }

    private fun expandIdentifierToken(token: String): String {
        val match = Regex("^([A-Za-z]?)(\\d+)([A-Za-z]?)$").matchEntire(token) ?: return token
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val suffix = match.groupValues[3]
        val pieces = buildList {
            if (prefix.isNotEmpty()) add(EnglishSpeechTextNormaliser.letterName(prefix[0]))
            add(if (digits.length == 3 || digits.startsWith('0')) EnglishSpeechTextNormaliser.expandDigits(digits) else EnglishSpeechTextNormaliser.numberToWords(digits.toLong()))
            if (suffix.isNotEmpty()) add(EnglishSpeechTextNormaliser.letterName(suffix[0]))
        }
        return pieces.joinToString(" ")
    }
}
