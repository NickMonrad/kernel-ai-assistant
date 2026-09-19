package com.kernel.ai.core.memory.lists

import java.math.BigDecimal

/**
 * Why an exported shared-list package could not be used.
 *
 * Every one of these is detected before any Room mutation, so a rejected package leaves local
 * Lists state exactly as it was.
 */
enum class ListPackageFailure {
    /** The file or one of its records declares a format version this build cannot read. */
    UNSUPPORTED_VERSION,

    /** Structurally broken: bad separators, bad tokens, invalid identifiers or inconsistent state. */
    MALFORMED,

    /** Authenticated decryption failed: wrong key, tampered ciphertext, or tampered envelope. */
    UNAUTHENTICATED,

    /** The records describe different shared collections, or the envelope's key is not the invite's. */
    IDENTITY_MISMATCH,
}

class ListPackageException(
    val reason: ListPackageFailure,
    message: String,
) : IllegalArgumentException(message)

/**
 * One item's synchronised state as it travels inside a package.
 *
 * Local-only columns (`isFavourite`, `notificationTime`, local display projection) are deliberately
 * absent: the shared contract keeps them on the device that owns them.
 */
data class SharedItemSnapshot(
    val itemId: String,
    val text: String,
    val checked: Boolean,
    val dueAt: Long?,
    val parentItemId: String?,
    val orderKey: String,
    val lifecycle: ListLifecycle,
    val createdAt: Long,
    val textStamp: VersionStamp,
    val checkedStamp: VersionStamp,
    val dueAtStamp: VersionStamp,
    val placementStamp: VersionStamp,
    val lifecycleStamp: VersionStamp,
)

/** Per-actor delivery position for one collection, carried so the receiver need not replay it. */
data class SharedCheckpointSnapshot(
    val actorId: String,
    val highestContiguousSourceSequence: Long,
)

/**
 * Synchronised state of one shared collection: every item row including tombstones, with the field
 * stamps that decide later merges.
 */
data class SharedCollectionSnapshot(
    val collectionId: String,
    val canonicalTitle: String,
    val lifecycle: ListLifecycle,
    val createdAt: Long,
    val titleStamp: VersionStamp,
    val lifecycleStamp: VersionStamp,
    val items: List<SharedItemSnapshot>,
    val checkpoints: List<SharedCheckpointSnapshot>,
) {
    val activeItemCount: Int get() = items.count { it.lifecycle == ListLifecycle.ACTIVE }

    fun encode(): String = listOf(
        PAYLOAD_FORMAT_VERSION.toString(),
        collectionId.encodeToken(),
        canonicalTitle.encodeToken(),
        lifecycle.name,
        createdAt.toString(),
        titleStamp.logicalClock.toString(),
        titleStamp.actorId.encodeToken(),
        lifecycleStamp.logicalClock.toString(),
        lifecycleStamp.actorId.encodeToken(),
        checkpoints.encodeEntries { "${it.actorId.encodeToken()}:${it.highestContiguousSourceSequence}" },
        items.encodeEntries(::encodeItemEntry),
    ).joinToString(FIELD.toString())

    companion object {
        const val PAYLOAD_FORMAT_VERSION = 1
        private const val FIELD_COUNT = 11

        fun decode(raw: String): SharedCollectionSnapshot {
            val fields = raw.split(FIELD)
            if (fields.size != FIELD_COUNT || fields[0] != PAYLOAD_FORMAT_VERSION.toString()) {
                fail(ListPackageFailure.MALFORMED, "Unsupported shared Lists payload")
            }
            val snapshot = SharedCollectionSnapshot(
                collectionId = fields[1].requireToken("collectionId"),
                canonicalTitle = fields[2].requireToken("canonicalTitle"),
                lifecycle = fields[3].requireLifecycle(),
                createdAt = fields[4].requireLong("createdAt"),
                titleStamp = VersionStamp(
                    fields[5].requireLong("titleLogicalClock"),
                    fields[6].optionalToken(),
                ),
                lifecycleStamp = VersionStamp(
                    fields[7].requireLong("lifecycleLogicalClock"),
                    fields[8].optionalToken(),
                ),
                checkpoints = fields[9].decodeEntries().map(::decodeCheckpointEntry),
                items = fields[10].decodeEntries().map(::decodeItemEntry),
            )
            snapshot.validate()
            return snapshot
        }
    }
}

private const val ITEM_ENTRY_FIELD_COUNT = 18
private const val FIELD = '|'
private const val ENTRY = ';'
private const val ITEM_FIELD = ':'

private fun encodeItemEntry(item: SharedItemSnapshot): String = listOf(
    item.itemId.encodeToken(),
    item.text.encodeToken(),
    item.checked.toString(),
    item.dueAt?.toString() ?: NULL_TOKEN,
    item.parentItemId.encodeToken(),
    item.orderKey.encodeToken(),
    item.lifecycle.name,
    item.createdAt.toString(),
    item.textStamp.logicalClock.toString(),
    item.textStamp.actorId.encodeToken(),
    item.checkedStamp.logicalClock.toString(),
    item.checkedStamp.actorId.encodeToken(),
    item.dueAtStamp.logicalClock.toString(),
    item.dueAtStamp.actorId.encodeToken(),
    item.placementStamp.logicalClock.toString(),
    item.placementStamp.actorId.encodeToken(),
    item.lifecycleStamp.logicalClock.toString(),
    item.lifecycleStamp.actorId.encodeToken(),
).joinToString(ITEM_FIELD.toString())

private fun decodeItemEntry(entry: String): SharedItemSnapshot {
    val fields = entry.split(ITEM_FIELD)
    if (fields.size != ITEM_ENTRY_FIELD_COUNT) {
        fail(ListPackageFailure.MALFORMED, "Unsupported shared Lists item payload")
    }
    return SharedItemSnapshot(
        itemId = fields[0].requireToken("itemId"),
        text = fields[1].optionalToken(),
        checked = fields[2].requireBoolean("checked"),
        dueAt = fields[3].decodeNullableLong("dueAt"),
        parentItemId = fields[4].optionalToken().takeIf { it.isNotEmpty() },
        orderKey = fields[5].requireToken("orderKey"),
        lifecycle = fields[6].requireLifecycle(),
        createdAt = fields[7].requireLong("createdAt"),
        textStamp = VersionStamp(fields[8].requireLong("textLogicalClock"), fields[9].optionalToken()),
        checkedStamp = VersionStamp(fields[10].requireLong("checkedLogicalClock"), fields[11].optionalToken()),
        dueAtStamp = VersionStamp(fields[12].requireLong("dueAtLogicalClock"), fields[13].optionalToken()),
        placementStamp = VersionStamp(fields[14].requireLong("placementLogicalClock"), fields[15].optionalToken()),
        lifecycleStamp = VersionStamp(fields[16].requireLong("lifecycleLogicalClock"), fields[17].optionalToken()),
    )
}

private fun decodeCheckpointEntry(entry: String): SharedCheckpointSnapshot {
    val parts = entry.split(ITEM_FIELD)
    if (parts.size != 2) fail(ListPackageFailure.MALFORMED, "Unsupported shared Lists checkpoint payload")
    return SharedCheckpointSnapshot(
        actorId = parts[0].optionalToken(),
        highestContiguousSourceSequence = parts[1].requireLong("checkpoint sequence"),
    )
}

private fun <T> List<T>.encodeEntries(encode: (T) -> String): String =
    takeIf { it.isNotEmpty() }?.joinToString(ENTRY.toString(), transform = encode).encodeToken() ?: NULL_TOKEN

private fun String.decodeEntries(): List<String> =
    decodeToken()?.takeIf { it.isNotEmpty() }?.split(ENTRY) ?: emptyList()

/**
 * Public envelope of an exported package.
 *
 * It names the collection and the key, and carries the authenticated ciphertext. It never contains
 * list content, and it never carries the collection key itself.
 */
data class SharedCollectionEnvelope(
    val formatVersion: Int = FORMAT_VERSION,
    val packageId: String,
    val collectionId: String,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
) {
    /**
     * Metadata bound into the ciphertext as additional authenticated data, so renaming the package,
     * retargeting it at another collection or swapping its key identifier all fail authentication.
     */
    fun authenticatedData(): ByteArray =
        "$formatVersion|$packageId|$collectionId|$keyId".toByteArray(Charsets.UTF_8)

    fun encode(): String = listOf(
        RECORD,
        formatVersion.toString(),
        packageId,
        collectionId,
        keyId,
        nonce,
        ciphertext,
    ).joinToString(FIELD.toString())

    companion object {
        const val FORMAT_VERSION = 1
        internal const val RECORD = "P"
        private const val FIELD_COUNT = 7

        fun decode(raw: String): SharedCollectionEnvelope {
            val fields = raw.split(FIELD)
            if (fields.size != FIELD_COUNT || fields[0] != RECORD) {
                fail(ListPackageFailure.MALFORMED, "Not a shared Lists envelope")
            }
            val version = fields[1].requireInt("formatVersion")
            if (version != FORMAT_VERSION) {
                fail(ListPackageFailure.UNSUPPORTED_VERSION, "Unsupported shared Lists package version $version")
            }
            return SharedCollectionEnvelope(
                formatVersion = version,
                packageId = fields[2].requireRaw("packageId"),
                collectionId = fields[3].requireRaw("collectionId"),
                keyId = fields[4].requireRaw("keyId"),
                nonce = fields[5].requireRaw("nonce"),
                ciphertext = fields[6].requireRaw("ciphertext"),
            )
        }
    }
}

/**
 * Secret capability that lets a recipient open the package travelling with it.
 *
 * #1493 exchanges packages as user-transported files, so the invite rides in the same file; how a
 * capability is hosted, rotated or revoked is out of scope for this slice.
 */
data class SharedCollectionInvite(
    val formatVersion: Int = FORMAT_VERSION,
    val collectionId: String,
    val memberId: String,
    val role: String,
    val keyId: String,
    val collectionKey: String,
) {
    fun encode(): String = listOf(
        RECORD,
        formatVersion.toString(),
        collectionId.encodeToken(),
        memberId.encodeToken(),
        role.encodeToken(),
        keyId.encodeToken(),
        collectionKey.encodeToken(),
    ).joinToString(FIELD.toString())

    companion object {
        const val FORMAT_VERSION = 1
        const val ROLE_EDITOR = "editor"
        internal const val RECORD = "I"
        private const val FIELD_COUNT = 7

        fun decode(raw: String): SharedCollectionInvite {
            val fields = raw.split(FIELD)
            if (fields.size != FIELD_COUNT || fields[0] != RECORD) {
                fail(ListPackageFailure.MALFORMED, "Not a shared Lists invite")
            }
            val version = fields[1].requireInt("formatVersion")
            if (version != FORMAT_VERSION) {
                fail(ListPackageFailure.UNSUPPORTED_VERSION, "Unsupported shared Lists invite version $version")
            }
            return SharedCollectionInvite(
                formatVersion = version,
                collectionId = fields[2].requireToken("collectionId"),
                memberId = fields[3].requireToken("memberId"),
                role = fields[4].requireToken("role"),
                keyId = fields[5].requireToken("keyId"),
                collectionKey = fields[6].requireToken("collectionKey"),
            )
        }
    }
}

/** One exported file: the public envelope plus the invite that unlocks it. */
data class SharedCollectionPackageFile(
    val invite: SharedCollectionInvite,
    val envelope: SharedCollectionEnvelope,
) {
    val collectionId: String get() = envelope.collectionId

    fun encode(): String = listOf(
        FILE_FORMAT_VERSION.toString(),
        invite.encode(),
        envelope.encode(),
    ).joinToString("\n")

    companion object {
        const val FILE_FORMAT_VERSION = 1
        private const val RECORD_COUNT = 2

        fun decode(raw: String): SharedCollectionPackageFile {
            val lines = raw.trim().split('\n').map { it.trim() }
            if (lines.size != RECORD_COUNT + 1 || lines[0] != FILE_FORMAT_VERSION.toString()) {
                fail(ListPackageFailure.MALFORMED, "Not a Jandal shared list package")
            }
            val records = lines.drop(1)
            val inviteRecord = records.firstOrNull { it.startsWith(SharedCollectionInvite.RECORD) }
            val envelopeRecord = records.firstOrNull { it.startsWith(SharedCollectionEnvelope.RECORD) }
            if (inviteRecord == null || envelopeRecord == null) {
                fail(ListPackageFailure.MALFORMED, "Shared list package is missing a record")
            }
            val invite = SharedCollectionInvite.decode(inviteRecord)
            val envelope = SharedCollectionEnvelope.decode(envelopeRecord)
            if (invite.collectionId != envelope.collectionId || invite.keyId != envelope.keyId) {
                fail(ListPackageFailure.IDENTITY_MISMATCH, "Shared list package records do not agree")
            }
            return SharedCollectionPackageFile(invite = invite, envelope = envelope)
        }
    }
}

/** What one import changed, so the caller can reconcile reminders and report the outcome. */
data class ListPackageImportResult(
    val listId: Long,
    val collectionCreated: Boolean,
    val itemsCreated: Int,
    val itemsUpdated: Int,
    val checkedStateMutation: CheckedStateMutation = CheckedStateMutation(),
)

private fun SharedCollectionSnapshot.validate() {
    if (items.map { it.itemId }.toSet().size != items.size) {
        fail(ListPackageFailure.MALFORMED, "Shared list package repeats an item identity")
    }
    if (items.any { it.parentItemId != null && it.parentItemId == it.itemId }) {
        fail(ListPackageFailure.MALFORMED, "Shared list package makes an item its own parent")
    }
    if (checkpoints.map { it.actorId }.toSet().size != checkpoints.size) {
        fail(ListPackageFailure.MALFORMED, "Shared list package repeats a checkpoint actor")
    }
    items.forEach { item ->
        // Ordering is exact-decimal authority: an unparseable key would silently reorder a list.
        runCatching { BigDecimal(item.orderKey) }
            .getOrElse { fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid order key") }
    }
}

private fun String.requireToken(name: String): String =
    optionalToken().takeIf { it.isNotBlank() }
        ?: fail(ListPackageFailure.MALFORMED, "Shared list package is missing $name")

/** Validates a plain envelope field: present and free of the record separators. */
private fun String.requireRaw(name: String): String {
    if (isBlank() || contains(FIELD) || contains('\n')) {
        fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid $name")
    }
    return this
}

private fun String.optionalToken(): String =
    runCatching { decodeToken() }
        .getOrElse { fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid value") }
        .orEmpty()

private fun String.requireLong(name: String): Long =
    toLongOrNull() ?: fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid $name")

private fun String.decodeNullableLong(name: String): Long? =
    takeIf { it != NULL_TOKEN }?.let { requireLong(name) }

private fun String.requireInt(name: String): Int =
    toIntOrNull() ?: fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid $name")

private fun String.requireBoolean(name: String): Boolean =
    toBooleanStrictOrNull() ?: fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid $name")

private fun String.requireLifecycle(): ListLifecycle =
    runCatching { ListLifecycle.valueOf(this) }
        .getOrElse { fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid lifecycle") }

internal fun fail(reason: ListPackageFailure, message: String): Nothing =
    throw ListPackageException(reason, message)
