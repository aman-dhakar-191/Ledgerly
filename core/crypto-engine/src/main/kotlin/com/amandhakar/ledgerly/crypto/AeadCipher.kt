package com.amandhakar.ledgerly.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption of a single blob, per docs/crypto.md:
 * fresh random 12-byte nonce every call, AAD = blobId || schemaVersion binds
 * the ciphertext to its identity so a swapped blobId fails authentication.
 *
 * Nonce reuse under the same key breaks GCM catastrophically — [encrypt] must
 * never be called with an explicit/derived nonce, only [SecureRandom].
 */
object AeadCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    const val NONCE_LENGTH_BYTES = 12

    private val secureRandom = SecureRandom()

    fun encrypt(dek: ByteArray, blobId: String, schemaVersion: Int, plaintext: ByteArray): EncryptedBlob {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(aad(blobId, schemaVersion))
        val output = cipher.doFinal(plaintext)
        val ciphertext = output.copyOfRange(0, output.size - TAG_LENGTH_BYTES)
        val tag = output.copyOfRange(output.size - TAG_LENGTH_BYTES, output.size)
        return EncryptedBlob(nonce, ciphertext, tag, schemaVersion)
    }

    /** Throws [GeneralSecurityException] (e.g. AEADBadTagException) if authentication fails. */
    fun decrypt(dek: ByteArray, blobId: String, blob: EncryptedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(dek, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.nonce),
        )
        cipher.updateAAD(aad(blobId, blob.schemaVersion))
        return cipher.doFinal(blob.ciphertext + blob.tag)
    }

    private fun aad(blobId: String, schemaVersion: Int): ByteArray {
        val versionBytes = byteArrayOf(
            (schemaVersion ushr 24).toByte(),
            (schemaVersion ushr 16).toByte(),
            (schemaVersion ushr 8).toByte(),
            schemaVersion.toByte(),
        )
        return blobId.toByteArray(Charsets.UTF_8) + versionBytes
    }
}
