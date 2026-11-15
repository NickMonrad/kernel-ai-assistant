package com.kernel.ai.core.memory.lists

import java.util.UUID

/**
 * Exchanges one shared list as a versioned, encrypted package file.
 *
 * The same snapshot, stamps, tombstones and hierarchy a peer transport would carry, wrapped in an
 * authenticated envelope. Both directions are pure format work: [export] seals an
 * already-read snapshot and [inspect] validates and opens one, so a caller can show what a package
 * contains before deciding to apply it through the authoritative list mutation seam.
 */
object ListPackageExchange {
    /**
     * Seals a snapshot as package text.
     *
     * A fresh collection key is generated for every export and travels in the invite inside the same
     * file, which is what lets a recipient open the package without any key service and guarantees a
     * nonce is never reused under a key. The invite is a secret capability: whoever holds the file can
     * read and edit the shared list.
     */
    fun export(snapshot: SharedCollectionSnapshot, memberId: String): String {
        val key = ListPackageCrypto.generateKey()
        val keyId = ListPackageCrypto.keyId(key)
        val metadata = SharedCollectionEnvelope(
            packageId = UUID.randomUUID().toString(),
            collectionId = snapshot.collectionId,
            keyId = keyId,
            nonce = "",
            ciphertext = "",
        )
        val encrypted = ListPackageCrypto.encrypt(
            key = key,
            plaintext = snapshot.encode().toByteArray(Charsets.UTF_8),
            authenticatedData = metadata.authenticatedData(),
        )
        return SharedCollectionPackageFile(
            invite = SharedCollectionInvite(
                collectionId = snapshot.collectionId,
                memberId = memberId.ifBlank { "unknown" },
                role = SharedCollectionInvite.ROLE_EDITOR,
                keyId = keyId,
                collectionKey = ListPackageCrypto.encode(key),
            ),
            envelope = metadata.copy(
                nonce = ListPackageCrypto.encodeBytes(encrypted.nonce),
                ciphertext = ListPackageCrypto.encodeBytes(encrypted.ciphertext),
            ),
        ).encode()
    }

    /**
     * Validates, authenticates and decodes a package without touching local state.
     *
     * Structure, format version, invite/envelope agreement, key identifier and the authenticated
     * ciphertext are all checked here, so a rejected package never reaches Room.
     */
    fun inspect(raw: String): SharedCollectionSnapshot {
        val file = SharedCollectionPackageFile.decode(raw)
        val key = ListPackageCrypto.decodeKey(file.invite.collectionKey)
        if (ListPackageCrypto.keyId(key) != file.envelope.keyId) {
            fail(ListPackageFailure.IDENTITY_MISMATCH, "Shared list package key does not match its invite")
        }
        val plaintext = ListPackageCrypto.decrypt(
            key = key,
            nonce = ListPackageCrypto.decodeBytes(file.envelope.nonce),
            ciphertext = ListPackageCrypto.decodeBytes(file.envelope.ciphertext),
            authenticatedData = file.envelope.authenticatedData(),
        )
        val snapshot = SharedCollectionSnapshot.decode(String(plaintext, Charsets.UTF_8))
        if (snapshot.collectionId != file.collectionId) {
            fail(ListPackageFailure.IDENTITY_MISMATCH, "Shared list package records do not agree")
        }
        return snapshot
    }
}
