package com.kernel.ai.core.memory.lists

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListPackageExchangeTest {
    private val collectionId = "3f1d0f5c-1111-4222-8333-444455556666"

    @Test
    fun `an exported package round trips the whole shared snapshot`() {
        val snapshot = snapshot()

        val inspected = ListPackageExchange.inspect(ListPackageExchange.export(snapshot, "member-a"))

        assertEquals(snapshot, inspected)
    }

    @Test
    fun `the envelope exposes no list content and no collection key`() {
        val snapshot = snapshot()

        val exported = ListPackageExchange.export(snapshot, "member-a")

        assertFalse(exported.contains("Secret shopping list"), "canonical title leaked into the package")
        assertFalse(exported.contains("MILK-SECRET"), "item text leaked into the package")
        val invite = SharedCollectionInvite.decode(exported.lines()[1])
        val envelope = SharedCollectionEnvelope.decode(exported.lines()[2])
        assertFalse(envelope.encode().contains(invite.collectionKey), "collection key leaked into the envelope")
        assertEquals(ListPackageCrypto.keyId(ListPackageCrypto.decodeKey(invite.collectionKey)), envelope.keyId)
    }

    @Test
    fun `every export uses a fresh key and nonce`() {
        val first = ListPackageExchange.export(snapshot(), "member-a")
        val second = ListPackageExchange.export(snapshot(), "member-a")

        val firstEnvelope = SharedCollectionEnvelope.decode(first.lines()[2])
        val secondEnvelope = SharedCollectionEnvelope.decode(second.lines()[2])
        assertNotEquals(firstEnvelope.nonce, secondEnvelope.nonce)
        assertNotEquals(firstEnvelope.keyId, secondEnvelope.keyId)
    }

    @Test
    fun `a tampered ciphertext fails authentication`() {
        val tampered = reencode { file ->
            file.copy(envelope = file.envelope.copy(ciphertext = flipLastCharacter(file.envelope.ciphertext)))
        }

        assertEquals(
            ListPackageFailure.UNAUTHENTICATED,
            assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(tampered) }.reason,
        )
    }

    @Test
    fun `tampered envelope metadata fails authentication`() {
        val tampered = reencode { file ->
            file.copy(envelope = file.envelope.copy(packageId = "00000000-0000-4000-8000-000000000000"))
        }

        assertEquals(
            ListPackageFailure.UNAUTHENTICATED,
            assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(tampered) }.reason,
        )
    }

    @Test
    fun `a package carrying a different key than its invite is rejected`() {
        val tampered = reencode { file ->
            file.copy(
                invite = file.invite.copy(
                    collectionKey = ListPackageCrypto.encode(ListPackageCrypto.generateKey()),
                ),
            )
        }

        assertEquals(
            ListPackageFailure.IDENTITY_MISMATCH,
            assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(tampered) }.reason,
        )
    }

    @Test
    fun `an unsupported package version is rejected as incompatible`() {
        val newer = reencode { file -> file.copy(envelope = file.envelope.copy(formatVersion = 2)) }

        assertEquals(
            ListPackageFailure.UNSUPPORTED_VERSION,
            assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(newer) }.reason,
        )
    }

    @Test
    fun `a file version this build cannot read is rejected before decryption`() {
        val exported = ListPackageExchange.export(snapshot(), "member-a")
        val newer = exported.replaceFirst("1\n", "2\n")

        assertEquals(
            ListPackageFailure.MALFORMED,
            assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(newer) }.reason,
        )
    }

    @Test
    fun `files that are not packages are rejected without touching local state`() {
        listOf("", "hello", "1\ngarbage\n", "1\nI|1|a|b|c|d|e").forEach { raw ->
            val failure = assertThrows(ListPackageException::class.java) { ListPackageExchange.inspect(raw) }
            assertTrue(
                failure.reason in setOf(ListPackageFailure.MALFORMED, ListPackageFailure.UNSUPPORTED_VERSION),
                "unexpected failure for \"$raw\": ${failure.reason}",
            )
        }
    }

    @Test
    fun `payload validation rejects repeated identities, self parents and bad order keys`() {
        val repeated = snapshot(
            items = listOf(item(itemId = "duplicate"), item(itemId = "duplicate")),
        )
        val selfParent = snapshot(items = listOf(item(itemId = "self", parentItemId = "self")))
        val badOrderKey = snapshot(items = listOf(item(orderKey = "not-a-decimal")))

        listOf(repeated, selfParent, badOrderKey).forEach { invalid ->
            val failure = assertThrows(ListPackageException::class.java) {
                SharedCollectionSnapshot.decode(invalid.encode())
            }
            assertEquals(ListPackageFailure.MALFORMED, failure.reason)
        }
    }

    private fun flipLastCharacter(value: String): String {
        val last = value.last()
        val replacement = if (last == 'A') 'B' else 'A'
        return value.dropLast(1) + replacement
    }

    /** Re-encodes a freshly exported package with one record deliberately altered. */
    private fun reencode(transform: (SharedCollectionPackageFile) -> SharedCollectionPackageFile): String =
        transform(SharedCollectionPackageFile.decode(ListPackageExchange.export(snapshot(), "member-a"))).encode()

    private fun snapshot(items: List<SharedItemSnapshot> = defaultItems()): SharedCollectionSnapshot =
        SharedCollectionSnapshot(
            collectionId = collectionId,
            canonicalTitle = "Secret shopping list",
            lifecycle = ListLifecycle.ACTIVE,
            createdAt = 1_700_000_000_000L,
            titleStamp = VersionStamp(4, "member-a"),
            lifecycleStamp = VersionStamp(4, "member-a"),
            items = items,
            checkpoints = listOf(
                SharedCheckpointSnapshot("member-a", 7),
                SharedCheckpointSnapshot("member-b", 3),
            ),
        )

    private fun defaultItems(): List<SharedItemSnapshot> = listOf(
        item(itemId = "parent", text = "MILK-SECRET", orderKey = "0"),
        item(itemId = "child", text = "Bread", parentItemId = "parent", orderKey = "1", checked = true),
        item(itemId = "gone", text = "Eggs", orderKey = "2", lifecycle = ListLifecycle.DELETED),
    )

    private fun item(
        itemId: String = "item",
        text: String = "Item",
        checked: Boolean = false,
        dueAt: Long? = null,
        parentItemId: String? = null,
        orderKey: String = "0",
        lifecycle: ListLifecycle = ListLifecycle.ACTIVE,
        createdAt: Long = 1_700_000_000_000L,
        stamp: VersionStamp = VersionStamp(2, "member-a"),
    ): SharedItemSnapshot = SharedItemSnapshot(
        itemId = itemId,
        text = text,
        checked = checked,
        dueAt = dueAt,
        parentItemId = parentItemId,
        orderKey = orderKey,
        lifecycle = lifecycle,
        createdAt = createdAt,
        textStamp = stamp,
        checkedStamp = stamp,
        dueAtStamp = stamp,
        placementStamp = stamp,
        lifecycleStamp = stamp,
    )
}
