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
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+")
    private val clauseBoundary = Regex("(?<=[,;:])\\s+")
    /** Sentences longer than this many phonemes, joined by " and ", are split at the join (see [phonemizeChunks]). */
    private const val CLAUSE_SPLIT_PHONEME_LENGTH = 100
    /** Minimum characters on each side of an " and " clause split, so a split never leaves a fragment. */
    private const val MIN_CLAUSE_SPLIT_CHARS = 20
    /** Number words, plus the respelled teen stems (`thir`, `fif`, `eigh`) that [splitTeens] produces. */
    private const val NUMBER_WORD_ALTERNATION = "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
        "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|" +
        "ninety|hundred|thousand|million|billion|trillion|thir|fif|eigh"
    private val numberWordAfterAnd = Regex("^and (?:$NUMBER_WORD_ALTERNATION)\\b")
    private val numberWordInText = Regex("\\b(?:$NUMBER_WORD_ALTERNATION)\\b", RegexOption.IGNORE_CASE)

    private val teenSplitPatterns = listOf(
        "thirteen" to "thir teen", "fourteen" to "four teen", "fifteen" to "fif teen", "sixteen" to "six teen",
        "seventeen" to "seven teen", "eighteen" to "eigh teen", "nineteen" to "nine teen",
    ).map { (word, split) -> Regex("\\b$word\\b", RegexOption.IGNORE_CASE) to split }

    /** Splits teen words so eSpeak keeps the unreduced "teen" (#2115 Inflect teen round). */
    private fun splitTeens(text: String): String = teenSplitPatterns.fold(text) { acc, (pattern, split) ->
        pattern.replace(acc) { match ->
            if (match.value[0].isUpperCase()) split.replaceFirstChar { it.uppercaseChar() } else split
        }
    }

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

    /**
     * Inflect only: "one million two hundred thousand dollars and fifty cents" garbled when read as one run (the
     * amount came out as $1,232,050). A comma after the scale word and a sentence break before the cents read it
     * as 1,200,000.50 on the S23U (#2115). Kokoro reads the unchanged text correctly, so this stays out of the shared normaliser.
     * The number phrase may also contain "and" and respelled teens ("two hundred and fifty thousand", "fif teen thousand").
     */
    private val millionCentsClause = Regex("""\b(million|billion) ((?:(?:$NUMBER_WORD_ALTERNATION|and|teen) )+dollars) and ((?:[a-z]+ )*?[a-z]+) cents\b""")

    fun normalize(text: String): String {
        // "(09) 123 4567" → "09 123 4567" before brackets become commas, so the shared normaliser reads grouped digits.
        var value = text.replace(Regex("""\((0\d{1,2})\)\s*""")) { match -> "${match.groupValues[1]} " }
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
            // "Unit 3/45" is a flat address read by EnglishSpeechTextNormaliser; a slash form is not spelled as an identifier.
            Regex("\\b(apartment|apt\\.?|suite|unit|room|flight|extension|order|invoice|locker|aisle|gate)\\s+([A-Za-z]?\\d{1,4}[A-Za-z]?)(?!/)\\b", RegexOption.IGNORE_CASE),
        ) { match -> "${match.groupValues[1]} ${expandIdentifierToken(match.groupValues[2])}" }
        value = value.replace(
            Regex("\\b(\\d{3})(?=\\s+(?:North|South|East|West)\\b)", RegexOption.IGNORE_CASE),
        ) { match -> EnglishSpeechTextNormaliser.expandDigits(match.groupValues[1]) }
        value = EnglishSpeechTextNormaliser.normalize(value)
        value = splitTeens(value)
        value = millionCentsClause.replace(value) { match ->
            "${match.groupValues[1]}, ${match.groupValues[2]}. And ${match.groupValues[3]} cents"
        }
        value = value.replace(Regex("\\b[A-Z]{2,}\\b")) { match ->
            match.value.map { EnglishSpeechTextNormaliser.letterName(it) }.joinToString(" ")
        }

        value = value.replace(Regex(",(?:\\s*,)+"), ",")
            .replace(Regex(",\\s*([.!?])"), "$1")
            .replace(Regex("\\s+([,;:.!?])"), "$1")
            .replace(Regex("([,;:.!?])(?=\\S)"), "$1 ")
        return value.replace(whitespace, " ").trim()
    }

    /** Upper bound on characters per speech chunk; keeps first-audio latency near one short sentence. */
    const val MAX_SPEECH_CHUNK_CHARS = 120

    /**
     * Splits normalized text into speech chunks of at most [maxChunkChars] characters, greedily packing whole
     * sentences and falling back to clause, then word, boundaries only for pieces that are still too long.
     * Chunks are joined in order with single spaces, so no text is dropped or reordered.
     */
    fun speechChunks(normalizedText: String, maxChunkChars: Int = MAX_SPEECH_CHUNK_CHARS): List<String> {
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }
        val text = normalizedText.trim()
        if (text.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        fun pack(piece: String) {
            when {
                current.isEmpty() -> current.append(piece)
                current.length + 1 + piece.length <= maxChunkChars -> current.append(' ').append(piece)
                else -> {
                    chunks += current.toString()
                    current = StringBuilder(piece)
                }
            }
        }

        for (sentence in text.split(sentenceBoundary)) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            for (piece in boundedPieces(trimmed, maxChunkChars)) pack(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * Lazily phonemizes [speechChunks] one chunk at a time, so each chunk can be synthesized before the next is
     * phonemized. Phoneme length stays under the runner's cap; the phonemizer runs only when the sequence is consumed.
     */
    fun phonemizeSpeechChunks(
        normalizedText: String,
        maxChunkChars: Int = MAX_SPEECH_CHUNK_CHARS,
        maxPhonemeLength: Int = InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH,
        phonemize: (String) -> String,
    ): Sequence<PhonemizedChunk> = speechChunks(normalizedText, maxChunkChars).asSequence().flatMap { chunk ->
        phonemizeChunks(
            normalizedText = chunk,
            maxPhonemeLength = maxPhonemeLength,
            phonemize = phonemize,
        ).asSequence()
    }

    /**
     * Lazy synthesis units for one raw reply: speakable text, normalized, speech chunks, then phonemized pieces in
     * order. A part that cannot be normalized or phonemized goes to [onSkip] and the rest of the text still yields
     * (the reply stream's failure contract).
     */
    fun speechPieces(
        rawText: String,
        onSkip: (Exception) -> Unit,
        phonemize: (String) -> String,
    ): Sequence<PhonemizedChunk> = sequence {
        val normalized = try {
            normalize(SpeechCardText.speakable(rawText))
        } catch (error: Exception) {
            onSkip(error)
            return@sequence
        }
        for (speechChunk in speechChunks(normalized)) {
            val pieces = try {
                phonemizeChunks(speechChunk, phonemize = phonemize)
            } catch (error: Exception) {
                onSkip(error)
                continue
            }
            yieldAll(pieces)
        }
    }

    private fun boundedPieces(sentence: String, maxChunkChars: Int): List<String> {
        if (sentence.length <= maxChunkChars) return listOf(sentence)
        return sentence.split(clauseBoundary).flatMap { clause ->
            val trimmed = clause.trim()
            if (trimmed.length <= maxChunkChars) listOf(trimmed) else wordPieces(trimmed, maxChunkChars)
        }
    }

    private fun wordPieces(text: String, maxChunkChars: Int): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(whitespace).filter(String::isNotEmpty)) {
            when {
                current.isEmpty() -> current.append(word)
                current.length + 1 + word.length <= maxChunkChars -> current.append(' ').append(word)
                else -> {
                    pieces += current.toString()
                    current.setLength(0)
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    fun applyPhonemeOverrides(phonemes: String): String {
        var value = Normalizer.normalize(phonemes, Normalizer.Form.NFD)
        value = value.replace("sˈæskɐtʃˌuːən", "sɐskˈætʃəwən")
        value = value.replace("flʊɹɹˈɛsənt", "flʊˈɹɛsənt")
        value = value.replace("θˈaʊzənd", "θˈaʊzˌænd")
        value = value.replace("mˈɪliən", "mˈɪljən")
        // eSpeak reads the split "nine teen" with a stress on both halves; "nineteen" is naɪntˈiːn (#2118 owner pick).
        value = value.replace(Regex("(?<!\\S)nˈaɪn tˈiːn(?=[\\s.,;:!?]|$)"), "naɪntˈiːn")
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
     * A text longer than [clauseSplitPhonemeLength] that contains a number word is first split at " and " joins between
     * clauses, nearest the middle first. Inflect garbled "fifteen thousand" in "The stadium holds fifteen thousand people
     * and cost one million …" (#2115) while the same phrase alone rendered correctly. Joins before a number word ("two
     * thousand and five", "two thousand and eighteen") are kept, as are joins that would leave a side under
     * [MIN_CLAUSE_SPLIT_CHARS] characters.
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
        val clauses = if (completePhonemes.length > clauseSplitPhonemeLength && numberWordInText.containsMatchIn(text)) {
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

    /**
     * Splits at " and " joins that begin a clause, nearest the middle first, until each part fits or no join is left.
     * Each side keeps at least [MIN_CLAUSE_SPLIT_CHARS] characters, so a split never leaves a fragment like "Fish."
     */
    private fun splitAtAndClauses(text: String, softLength: Int, phonemize: (String) -> String): List<String> {
        if (phonemize(text).length <= softLength) return listOf(text)
        val middle = text.length / 2
        val join = Regex(" and ").findAll(text)
            .map { it.range.first }
            .filterNot { numberWordAfterAnd.containsMatchIn(text.substring(it + 1)) }
            .filter { it >= MIN_CLAUSE_SPLIT_CHARS && text.length - it - 1 >= MIN_CLAUSE_SPLIT_CHARS }
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
