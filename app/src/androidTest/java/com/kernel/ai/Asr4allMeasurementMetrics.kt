package com.kernel.ai

internal object Asr4allMeasurementMetrics {
    data class Wer(val edits: Int, val referenceWords: Int) {
        val rate: Double get() = if (referenceWords == 0) if (edits == 0) 0.0 else 1.0 else edits.toDouble() / referenceWords
    }

    fun tokens(text: String): List<String> {
        val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return if (normalized.isEmpty()) emptyList() else normalized.split(Regex("\\s+"))
    }

    fun wer(reference: String, hypothesis: String): Wer {
        val ref = tokens(reference)
        val hyp = tokens(hypothesis)
        var previous = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val current = IntArray(hyp.size + 1)
            current[0] = i
            for (j in 1..hyp.size) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1)
            previous = current
        }
        return Wer(previous[hyp.size], ref.size)
    }

    fun aggregate(values: Iterable<Wer>): Wer = values.fold(Wer(0, 0)) { a, b -> Wer(a.edits + b.edits, a.referenceWords + b.referenceWords) }
}
