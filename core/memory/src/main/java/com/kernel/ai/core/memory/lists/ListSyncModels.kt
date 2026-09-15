package com.kernel.ai.core.memory.lists

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Deterministic Lamport-style version stamp from the shared Lists contract. */
data class VersionStamp(
    val logicalClock: Long,
    val actorId: String,
) : Comparable<VersionStamp> {
    override fun compareTo(other: VersionStamp): Int =
        logicalClock.compareTo(other.logicalClock).takeIf { it != 0 }
            ?: actorId.compareTo(other.actorId)
}

enum class ListLifecycle { ACTIVE, DELETED }

enum class ListChangeOperation {
    CREATE_COLLECTION,
    SET_COLLECTION_TITLE,
    DELETE_COLLECTION,
    RESTORE_COLLECTION,
    CREATE_ITEM,
    SET_ITEM_TEXT,
    SET_ITEM_CHECKED,
    SET_ITEM_DUE_AT,
    SET_ITEM_PLACEMENT,
    DELETE_ITEM,
    RESTORE_ITEM,
}
data class ListChangePayload(
    val canonicalTitle: String? = null,
    val text: String? = null,
    val checked: Boolean? = null,
    val dueAt: Long? = null,
    val parentItemId: String? = null,
    val orderKey: String? = null,
) {
    /** Compact versioned payload; URL-safe Base64 tokens cannot contain the field separator. */
    fun encode(): String = listOf(
        "1",
        canonicalTitle.encodeToken(),
        text.encodeToken(),
        checked?.toString() ?: NULL_TOKEN,
        dueAt?.toString() ?: NULL_TOKEN,
        parentItemId.encodeToken(),
        orderKey.encodeToken(),
    ).joinToString("|")

    companion object {
        private const val NULL_TOKEN = "~"

        fun decode(raw: String): ListChangePayload {
            val fields = raw.split('|')
            require(fields.size == 7 && fields[0] == "1") { "Unsupported Lists payload" }
            return ListChangePayload(
                canonicalTitle = fields[1].decodeToken(),
                text = fields[2].decodeToken(),
                checked = fields[3].takeUnless { it == NULL_TOKEN }?.toBooleanStrict(),
                dueAt = fields[4].takeUnless { it == NULL_TOKEN }?.toLong(),
                parentItemId = fields[5].decodeToken(),
                orderKey = fields[6].decodeToken(),
            )
        }
    }
}

private fun String?.encodeToken(): String =
    this?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) } ?: "~"

private fun String.decodeToken(): String? =
    takeUnless { it == "~" }?.let { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }

data class ListChange(
    val formatVersion: Int = 1,
    val changeId: String,
    val collectionId: String,
    val targetId: String,
    val actorId: String,
    val sourceSequence: Long,
    val stamp: VersionStamp,
    val operation: ListChangeOperation,
    val payload: ListChangePayload = ListChangePayload(),
)

object OrderKey {
    fun canonical(value: String): String = BigDecimal(value).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    fun compare(left: String, right: String): Int = BigDecimal(left).compareTo(BigDecimal(right))
    fun forIndex(index: Int): String = index.toString()
}

data class EffectiveHierarchy(
    val parentByChild: Map<String, String>,
    val topLevelItemIds: List<String>,
)

data class HierarchyItem(
    val itemId: String,
    val parentItemId: String?,
    val orderKey: String,
    val placementStamp: VersionStamp,
    val active: Boolean = true,
)

/** Deterministic two-level effective hierarchy derivation; requested placement is never mutated. */
object EffectiveHierarchyNormalizer {
    fun derive(items: Collection<HierarchyItem>): EffectiveHierarchy {
        val active = items.filter { it.active }.associateBy { it.itemId }
        val candidates = items.asSequence()
            .filter { it.active && it.parentItemId != null && active.containsKey(it.parentItemId) }
            .sortedWith(
                compareByDescending<HierarchyItem> { it.placementStamp }
                    .thenBy { it.itemId }
                    .thenBy { it.parentItemId },
            )
            .toList()

        val accepted = linkedMapOf<String, String>()
        val childrenByParent = mutableMapOf<String, MutableSet<String>>()
        fun createsCycle(child: String, parent: String): Boolean {
            var cursor: String? = parent
            while (cursor != null) {
                if (cursor == child) return true
                cursor = accepted[cursor]
            }
            return false
        }

        candidates.forEach { candidate ->
            val parent = candidate.parentItemId ?: return@forEach
            if (candidate.itemId == parent || accepted.containsKey(candidate.itemId) || accepted.containsKey(parent)) return@forEach
            if (createsCycle(candidate.itemId, parent)) return@forEach
            if (childrenByParent[parent].orEmpty().isNotEmpty()) return@forEach
            if (accepted.keys.any { accepted[it] == candidate.itemId }) return@forEach
            accepted[candidate.itemId] = parent
            childrenByParent.getOrPut(parent) { mutableSetOf() }.add(candidate.itemId)
        }

        val topLevel = active.keys.filterNot { accepted.containsKey(it) }
            .sortedWith { left, right ->
                val l = active.getValue(left)
                val r = active.getValue(right)
                OrderKey.compare(l.orderKey, r.orderKey).takeIf { it != 0 } ?: left.compareTo(right)
            }
        return EffectiveHierarchy(accepted, topLevel)
    }
}
