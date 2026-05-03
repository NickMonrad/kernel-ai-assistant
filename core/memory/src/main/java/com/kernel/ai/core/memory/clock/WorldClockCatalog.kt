package com.kernel.ai.core.memory.clock

import java.time.ZoneId
import java.util.Locale

data class WorldClockCandidate(
    val zoneId: String,
    val displayName: String,
    val subtitle: String,
)

sealed interface WorldClockResolution {
    data class Resolved(val candidate: WorldClockCandidate) : WorldClockResolution
    data class Ambiguous(val options: List<WorldClockCandidate>) : WorldClockResolution
    data object Unknown : WorldClockResolution
}

object WorldClockCatalog {
    private val aliasMap = mapOf(
        "jst" to "Asia/Tokyo",
        "utc" to "UTC",
        "gmt" to "Etc/GMT",
        "ottawa" to "America/Toronto",
    )

    private val popularZoneIds = listOf(
        "UTC",
        "Europe/London",
        "America/New_York",
        "America/Los_Angeles",
        "Europe/Paris",
        "Asia/Tokyo",
        "Australia/Sydney",
        "Pacific/Auckland",
        "Asia/Singapore",
        "Asia/Dubai",
    )

    private data class IndexedCandidate(
        val candidate: WorldClockCandidate,
        val exactTerms: Set<String>,
        val fuzzyText: String,
    )

    private val indexedCandidates: List<IndexedCandidate> by lazy {
        ZoneId.getAvailableZoneIds()
            .asSequence()
            .map { zoneId ->
                val candidate = WorldClockCandidate(
                    zoneId = zoneId,
                    displayName = zoneDisplayName(zoneId),
                    subtitle = zoneId.replace('_', ' '),
                )
                IndexedCandidate(
                    candidate = candidate,
                    exactTerms = buildSet {
                        add(normalize(zoneId))
                        add(normalize(candidate.displayName))
                        zoneId.split('/', '_').forEach { part ->
                            val normalized = normalize(part)
                            if (normalized.isNotBlank()) add(normalized)
                        }
                        aliasMap.forEach { (alias, mappedZoneId) ->
                            if (mappedZoneId == zoneId) add(alias)
                        }
                    },
                    fuzzyText = normalize("${candidate.displayName} ${candidate.subtitle}"),
                )
            }
            .sortedWith(compareBy<IndexedCandidate> { it.candidate.displayName }.thenBy { it.candidate.zoneId })
            .toList()
    }

    private val candidateByZoneId: Map<String, WorldClockCandidate> by lazy {
        indexedCandidates.associate { it.candidate.zoneId to it.candidate }
    }

    fun search(query: String, limit: Int = 12): List<WorldClockCandidate> {
        val normalized = normalize(query)
        if (normalized.isBlank()) {
            return popularZoneIds.mapNotNull(candidateByZoneId::get)
        }

        val exact = indexedCandidates.filter { normalized in it.exactTerms }
        if (exact.isNotEmpty()) return exact.take(limit).map { it.candidate }

        val prefix = indexedCandidates.filter {
            it.candidate.displayName.startsWith(query.trim(), ignoreCase = true) ||
                it.candidate.zoneId.startsWith(query.trim(), ignoreCase = true) ||
                it.exactTerms.any { term -> term.startsWith(normalized) }
        }
        if (prefix.isNotEmpty()) return prefix.take(limit).map { it.candidate }

        return indexedCandidates
            .asSequence()
            .filter { normalized in it.fuzzyText }
            .take(limit)
            .map { it.candidate }
            .toList()
    }

    fun resolve(query: String): WorldClockResolution {
        val normalized = normalize(query)
        if (normalized.isBlank()) return WorldClockResolution.Unknown

        aliasMap[normalized]?.let { zoneId ->
            candidateByZoneId[zoneId]?.let { return WorldClockResolution.Resolved(it) }
        }

        val exact = indexedCandidates.filter { normalized in it.exactTerms }
        if (exact.size == 1) return WorldClockResolution.Resolved(exact.single().candidate)
        if (exact.size > 1) return WorldClockResolution.Ambiguous(exact.take(5).map { it.candidate })

        val prefix = indexedCandidates.filter {
            it.candidate.displayName.startsWith(query.trim(), ignoreCase = true) ||
                it.candidate.zoneId.startsWith(query.trim(), ignoreCase = true)
        }
        if (prefix.size == 1) return WorldClockResolution.Resolved(prefix.single().candidate)
        if (prefix.size > 1) return WorldClockResolution.Ambiguous(prefix.take(5).map { it.candidate })

        val contains = indexedCandidates.filter { normalized in it.fuzzyText }
        if (contains.size == 1) return WorldClockResolution.Resolved(contains.single().candidate)
        if (contains.size > 1) return WorldClockResolution.Ambiguous(contains.take(5).map { it.candidate })

        return WorldClockResolution.Unknown
    }

    private fun zoneDisplayName(zoneId: String): String {
        if ('/' !in zoneId) return zoneId.replace('_', ' ')
        val parts = zoneId.split('/')
        val city = parts.last().replace('_', ' ')
        val qualifiers = parts.drop(1).dropLast(1).joinToString(" / ") { it.replace('_', ' ') }
        return if (qualifiers.isBlank()) city else "$city ($qualifiers)"
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('/', ' ')
            .replace(Regex("[^a-z0-9+ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
