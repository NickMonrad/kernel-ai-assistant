package com.kernel.ai.core.memory.lists

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side authenticated encryption for shared-list packages.
 *
 * AES-256-GCM with a fresh random 96-bit nonce per package and the envelope metadata bound in as
 * additional authenticated data. Exports generate a fresh collection key for each package and carry
 * it in the invite inside the same file, so a key is never reused across packages and the
 * nonce/key pair can never repeat.
 */
object ListPackageCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val KEY_ID_BYTES = 16

    private val random = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Opaque, stable identifier for [key]; it reveals nothing usable about the key itself. */
    fun keyId(key: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key).copyOf(KEY_ID_BYTES))

    fun encode(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    fun decodeKey(encoded: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrElse { fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid collection key") }
            .takeIf { it.size == KEY_BYTES }
            ?: fail(ListPackageFailure.MALFORMED, "Shared list package has an invalid collection key")

    /**
     * Encrypts [plaintext] with a fresh nonce and returns the nonce together with the ciphertext
     * (which carries the authentication tag).
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, authenticatedData: ByteArray): EncryptedPackage {
        if (key.size != KEY_BYTES) fail(ListPackageFailure.MALFORMED, "Invalid collection key length")
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(authenticatedData)
        return EncryptedPackage(nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    /**
     * Decrypts and authenticates a package payload.
     *
     * Any failure — wrong key, tampered ciphertext, tampered envelope metadata, malformed nonce — is
     * reported as [ListPackageFailure.UNAUTHENTICATED]; the two cases are deliberately not
     * distinguishable to a caller.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        authenticatedData: ByteArray,
    ): ByteArray {
        if (key.size != KEY_BYTES) fail(ListPackageFailure.MALFORMED, "Invalid collection key length")
        if (nonce.size != NONCE_BYTES) fail(ListPackageFailure.UNAUTHENTICATED, "Shared list package nonce is invalid")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            cipher.updateAAD(authenticatedData)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw ListPackageException(
                ListPackageFailure.UNAUTHENTICATED,
                "Shared list package could not be decrypted",
            )
        }
    }

    fun encodeBytes(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBytes(encoded: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrElse { fail(ListPackageFailure.MALFORMED, "Shared list package has invalid binary data") }
}

data class EncryptedPackage(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedPackage &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}
