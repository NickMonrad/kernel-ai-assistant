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
        val speechEndLatencyMs: Double? = null,
    )
    data class DecisionRow(
        val set: String,
        val model: String,
        val tier: String,
        val fixtureId: String,
        val repetition: Int,
        val pairedPcmSha256: String,
        val outcome: String,
        val wer: Wer?,
    )
    data class DecisionLane(
        val model: String,
        val tier: String,
        val expectedRows: Int,
        val observedRows: Int,
        val scoredRows: Int,
        val errorRows: Int,
        val missingRows: Int,
        val extraRows: Int,
        val duplicateRows: Int,
        val unscoredRows: Int,
        val inputMismatchKeys: Int,
        val decisionValid: Boolean,
        val wer: Wer?,
    )
    data class PairedDecision(
        val decisionValid: Boolean,
        val expectedRowsPerLane: Int,
        val unexpectedLaneRows: Int,
        val inputMismatchKeys: Int,
        val lanes: List<DecisionLane>,
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
        val speechEndLatencyCount: Int,
        val meanSpeechEndLatencyMs: Double?,
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

    /** Produces set/lane outcome and latency coverage; paired WER is gated separately. */
    fun summarize(rows: Iterable<Row>): List<Summary> =
        rows.groupBy { Triple(it.set, it.model, it.tier) }.map { (key, entries) ->
            val clipEndLatencies = entries.mapNotNull { it.clipEndLatencyMs }
            val endpointLatencies = entries.mapNotNull { it.endpointLatencyMs }
            val speechEndLatencies = entries.mapNotNull { it.speechEndLatencyMs }
            Summary(
                set = key.first,
                model = key.second,
                tier = key.third,
                rows = entries.size,
                finals = entries.count { it.outcome == "decoder_endpoint_final" || it.outcome == "forced_clip_flush_final" },
                noFinal = entries.count { it.outcome == "no_final" },
                errors = entries.count { it.outcome == "error" },
                werCoverage = entries.count { it.wer != null },
                clipEndLatencyCount = clipEndLatencies.size,
                meanClipEndLatencyMs = clipEndLatencies.takeIf { it.isNotEmpty() }?.average(),
                endpointLatencyCount = endpointLatencies.size,
                meanEndpointLatencyMs = endpointLatencies.takeIf { it.isNotEmpty() }?.average(),
                speechEndLatencyCount = speechEndLatencies.size,
                meanSpeechEndLatencyMs = speechEndLatencies.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedWith(compareBy(Summary::set, Summary::model, Summary::tier))
    fun pairedDecision(
        rows: Iterable<DecisionRow>,
        expectedFixtureIds: Set<String>,
        repetitions: Int,
        lanes: List<Lane>,
    ): PairedDecision {
        require(expectedFixtureIds.isNotEmpty() && repetitions > 0 && lanes.isNotEmpty())
        require(lanes.toSet().size == lanes.size)
        val expectedKeys = buildSet {
            for (fixtureId in expectedFixtureIds) {
                for (repetition in 0 until repetitions) add(fixtureId to repetition)
            }
        }
        val laneSet = lanes.toSet()
        val allRows = rows.toList()
        val unexpectedLaneRows = allRows.count { it.set != "owner-decision" || Lane(it.model, it.tier) !in laneSet }
        val ownerRows = allRows.filter { it.set == "owner-decision" }
        val rowsByLane = lanes.associateWith { lane ->
            ownerRows.filter { it.model == lane.model && it.tier == lane.tier }
        }
        val groupedByLane = rowsByLane.mapValues { (_, laneRows) ->
            laneRows.groupBy { it.fixtureId to it.repetition }
        }
        val inputMismatchKeys = expectedKeys.count { key ->
            val keyedRows = lanes.map { lane -> groupedByLane.getValue(lane)[key].orEmpty() }
            val hashes = keyedRows.flatten().map { it.pairedPcmSha256 }
            keyedRows.all { it.size == 1 } &&
                (hashes.distinct().size != 1 || hashes.any { !Regex("[0-9a-f]{64}").matches(it) })
        }
        val laneChecks = lanes.map { lane ->
            val laneRows = rowsByLane.getValue(lane)
            val groups = groupedByLane.getValue(lane)
            val actualKeys = groups.keys
            val missing = expectedKeys.count { it !in actualKeys }
            val extra = actualKeys.count { it !in expectedKeys }
            val duplicates = groups.values.sumOf { (it.size - 1).coerceAtLeast(0) }
            val unscored = laneRows.count { it.outcome !in SCORED_OUTCOMES || it.wer == null }
            DecisionLane(
                model = lane.model,
                tier = lane.tier,
                expectedRows = expectedKeys.size,
                observedRows = laneRows.size,
                scoredRows = laneRows.count { it.outcome in SCORED_OUTCOMES && it.wer != null },
                errorRows = laneRows.count { it.outcome == "error" },
                missingRows = missing,
                extraRows = extra,
                duplicateRows = duplicates,
                unscoredRows = unscored,
                inputMismatchKeys = inputMismatchKeys,
                decisionValid = false,
                wer = null,
            )
        }
        val decisionValid = unexpectedLaneRows == 0 && inputMismatchKeys == 0 && laneChecks.all {
            it.missingRows == 0 && it.extraRows == 0 && it.duplicateRows == 0 && it.unscoredRows == 0
        }
        val finalized = laneChecks.map { check ->
            val lane = lanes.single { it.model == check.model && it.tier == check.tier }
            val wer = if (decisionValid) {
                aggregate(expectedKeys.map { key -> groupedByLane.getValue(lane).getValue(key).single().wer!! })
            } else null
            check.copy(decisionValid = decisionValid, wer = wer)
        }
        return PairedDecision(
            decisionValid = decisionValid,
            expectedRowsPerLane = expectedKeys.size,
            unexpectedLaneRows = unexpectedLaneRows,
            inputMismatchKeys = inputMismatchKeys,
            lanes = finalized,
        )
    }

    private val SCORED_OUTCOMES = setOf("decoder_endpoint_final", "forced_clip_flush_final", "no_final")

    fun counterbalanced(lanes: List<Lane>, pairIndex: Int): List<Lane> {
        require(lanes.isNotEmpty())
        val start = Math.floorMod(pairIndex, lanes.size)
        return List(lanes.size) { lanes[(start + it) % lanes.size] }
    }
}
