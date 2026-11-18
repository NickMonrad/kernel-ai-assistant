package com.kernel.ai

internal object Asr4allMeasurementMetrics {
    data class Wer(val edits: Int, val referenceWords: Int) {
        val rate: Double get() = if (referenceWords == 0) if (edits == 0) 0.0 else 1.0 else edits.toDouble() / referenceWords
    }

    data class Lane(val model: String, val tier: String)
    data class Row(
        val set: String,
        val model: String,
        val tier: String,
        val outcome: String,
        val wer: Wer?,
        val clipEndLatencyMs: Double?,
        val endpointLatencyMs: Double?,
    )
    data class Summary(
        val set: String,
        val model: String,
        val tier: String,
        val rows: Int,
        val finals: Int,
        val noFinal: Int,
        val errors: Int,
        val werCoverage: Int,
        val clipEndLatencyCount: Int,
        val meanClipEndLatencyMs: Double?,
        val endpointLatencyCount: Int,
        val meanEndpointLatencyMs: Double?,
        val wer: Wer?,
    )

    fun tokens(text: String): List<String> {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .lowercase(java.util.Locale.ROOT)
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
            .replace(Regex("[’‘ʼ`´']"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
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

    /** The same function builds owner recommendations and text-free set/lane coverage. */
    fun summarize(rows: Iterable<Row>): List<Summary> =
        rows.groupBy { Triple(it.set, it.model, it.tier) }.map { (key, entries) ->
            val werRows = entries.mapNotNull { it.wer }
            val clipEndLatencies = entries.mapNotNull { it.clipEndLatencyMs }
            val endpointLatencies = entries.mapNotNull { it.endpointLatencyMs }
            Summary(
                set = key.first,
                model = key.second,
                tier = key.third,
                rows = entries.size,
                finals = entries.count { it.outcome == "decoder_endpoint_final" || it.outcome == "forced_clip_flush_final" },
                noFinal = entries.count { it.outcome == "no_final" },
                errors = entries.count { it.outcome == "error" },
                werCoverage = werRows.size,
                clipEndLatencyCount = clipEndLatencies.size,
                meanClipEndLatencyMs = clipEndLatencies.takeIf { it.isNotEmpty() }?.average(),
                endpointLatencyCount = endpointLatencies.size,
                meanEndpointLatencyMs = endpointLatencies.takeIf { it.isNotEmpty() }?.average(),
                wer = werRows.takeIf { it.isNotEmpty() }?.let(::aggregate),
            )
        }.sortedWith(compareBy(Summary::set, Summary::model, Summary::tier))

    fun counterbalanced(lanes: List<Lane>, pairIndex: Int): List<Lane> {
        require(lanes.isNotEmpty())
        val start = Math.floorMod(pairIndex, lanes.size)
        return List(lanes.size) { lanes[(start + it) % lanes.size] }
    }
}
