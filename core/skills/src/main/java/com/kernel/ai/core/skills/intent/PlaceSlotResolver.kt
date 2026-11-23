package com.kernel.ai.core.skills.intent

import java.text.Normalizer

/**
 * Snaps a place-slot value to a NZ gazetteer name when it is a close spelling of one (#2079).
 *
 * Only the place slots listed in [SLOTS] are read or rewritten, so ordinary text in other
 * slots, and any text outside a slot, is never changed. A value is snapped only when it is a
 * single-name phrase of at least [MIN_HEARD_LETTERS] letters, within a length-scaled edit distance
 * of exactly one gazetteer name. Sound-alike forms that are far apart in spelling (for example
 * "Funga Day") are not covered here; they belong in the STT glossary.
 */
internal object PlaceSlotResolver {

    /** Intent name to the place-slot parameter it fills. */
    private val SLOTS = mapOf(
        "navigate_to" to "destination",
        "get_weather" to "location",
        "get_time" to "location",
    )

    private const val MIN_HEARD_LETTERS = 6
    private const val MAX_HEARD_WORDS = 3

    // Declared before the keys below: object properties initialise in order.
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Common NZ towns and cities, in the spelling Jandal uses. Keep in sync with the STT glossary. */
    internal val GAZETTEER: List<String> = listOf(
        "Auckland", "Wellington", "Christchurch", "Hamilton", "Tauranga", "Napier", "Hastings",
        "Dunedin", "Palmerston North", "Nelson", "Rotorua", "New Plymouth", "Whangārei",
        "Invercargill", "Whanganui", "Gisborne", "Porirua", "Upper Hutt", "Lower Hutt", "Timaru",
        "Blenheim", "Taupō", "Pūkekohe", "Paraparaumu", "Masterton", "Levin", "Ōtaki", "Waikanae",
        "Feilding", "Whakatāne", "Cambridge", "Te Awamutu", "Matamata", "Morrinsville", "Thames",
        "Paeroa", "Tokoroa", "Tūrangi", "Taihape", "Hāwera", "Raglan", "Greymouth", "Westport",
        "Hokitika", "Queenstown", "Wānaka", "Alexandra", "Ōamaru", "Balclutha", "Gore", "Ashburton",
        "Rangiora", "Kaikōura", "Motueka", "Stratford", "Waitara", "Kerikeri", "Te Puke",
        "Ōpōtiki", "Wairoa", "Papakura", "Whitianga", "Dargaville", "Kaitaia", "Havelock North",
    )

    private val gazetteerKeys: List<Pair<String, String>> = GAZETTEER.map { it to foldKey(it) }

    /** Returns [params] with the place slot for [intentName] snapped to the gazetteer, or unchanged. */
    fun resolve(intentName: String, params: Map<String, String>): Map<String, String> {
        val slot = SLOTS[intentName] ?: return params
        val heard = params[slot] ?: return params
        val canonical = snap(heard) ?: return params
        return params + (slot to canonical)
    }

    /** The unique gazetteer name within the edit-distance limit of [heard], or null. */
    internal fun snap(heard: String): String? {
        if (heard.trim().split(WHITESPACE).size > MAX_HEARD_WORDS) return null
        val key = foldKey(heard)
        // An exact spelling only needs the canonical macrons; short names are allowed here.
        gazetteerKeys.firstOrNull { it.second == key }?.let { return it.first }
        if (key.length < MIN_HEARD_LETTERS) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var tied = false
        for ((name, nameKey) in gazetteerKeys) {
            val distance = levenshtein(key, nameKey)
            if (distance > maxDistance(nameKey.length)) continue
            when {
                distance < bestDistance -> {
                    best = name
                    bestDistance = distance
                    tied = false
                }
                distance == bestDistance && name != best -> tied = true
            }
        }
        return if (tied) null else best
    }

    /** Edit allowance scales with name length so short names cannot absorb unrelated words. */
    private fun maxDistance(nameLength: Int): Int = when {
        nameLength <= 7 -> 1
        nameLength <= 10 -> 2
        else -> 3
    }

    /** Lower-case ASCII letters only, macrons and other diacritics stripped. */
    private fun foldKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .filter { it in 'a'..'z' }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
