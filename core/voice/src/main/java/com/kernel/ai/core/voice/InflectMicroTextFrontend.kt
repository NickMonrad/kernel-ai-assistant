package com.kernel.ai.core.voice

import java.text.Normalizer

/**
 * Kotlin port of the pinned Inflect v2 reference frontend's deterministic text preparation.
 *
 * This intentionally stops before eSpeak: the custom Sherpa JNI seam supplies the reference
 * Piper/eSpeak phonemes, while this class owns all runtime text expansion and Inflect-specific
 * pronunciation corrections. Language-level numbers, money, dates and times come from [EnglishSpeechTextNormaliser].
 */
object InflectMicroTextFrontend {
    private val whitespace = Regex("\\s+")
    private val letterNames = mapOf(
        'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee",
        'F' to "eff", 'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay",
        'K' to "kay", 'L' to "ell", 'M' to "em", 'N' to "en", 'O' to "oh",
        'P' to "pee", 'Q' to "cue", 'R' to "ar", 'S' to "ess", 'T' to "tee",
        'U' to "you", 'V' to "vee", 'W' to "double you", 'X' to "ex", 'Y' to "why",
        'Z' to "zee",
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
            match.value.map { letterNames[it] ?: it.toString() }.joinToString(" ")
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
     */
    fun phonemizeChunks(
        normalizedText: String,
        maxPhonemeLength: Int = InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH,
        phonemize: (String) -> String,
    ): List<PhonemizedChunk> {
        require(maxPhonemeLength > 0) { "maxPhonemeLength must be positive" }
        val text = normalizedText.trim()
        if (text.isEmpty()) return emptyList()

        fun phonemizeChecked(value: String): String =
            phonemize(value).also { require(it.isNotBlank()) { "Phonemizer returned empty output." } }

        val completePhonemes = phonemizeChecked(text)
        if (completePhonemes.length <= maxPhonemeLength) {
            return listOf(PhonemizedChunk(text, completePhonemes))
        }

        val segments = text.split(Regex("(?<=[.!?;,:])\\s+"))
        val initialSegmentPhonemes = completePhonemes.takeIf { segments.size == 1 }
        return segments
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { segment ->
                phonemizeSegment(
                    text = segment,
                    maxPhonemeLength = maxPhonemeLength,
                    phonemize = ::phonemizeChecked,
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
            if (prefix.isNotEmpty()) add(letterNames[prefix[0].uppercaseChar()]!!)
            add(if (digits.length == 3 || digits.startsWith('0')) EnglishSpeechTextNormaliser.expandDigits(digits) else EnglishSpeechTextNormaliser.numberToWords(digits.toLong()))
            if (suffix.isNotEmpty()) add(letterNames[suffix[0].uppercaseChar()]!!)
        }
        return pieces.joinToString(" ")
    }
}
