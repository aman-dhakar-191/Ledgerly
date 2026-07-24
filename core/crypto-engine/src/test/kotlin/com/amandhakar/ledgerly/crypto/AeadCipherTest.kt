package com.amandhakar.ledgerly.crypto

import com.google.common.truth.Truth.assertThat
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AeadCipherTest {

    private fun randomDek(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `round trip preserves plaintext exactly`() {
        val dek = randomDek()
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val blob = AeadCipher.encrypt(dek, "txn-2026-07", schemaVersion = 1, plaintext = plaintext)
        val decrypted = AeadCipher.decrypt(dek, "txn-2026-07", blob)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `round trip preserves empty and large plaintext`() {
        val dek = randomDek()
        val empty = ByteArray(0)
        val large = ByteArray(2_000_000) { (it % 256).toByte() }
        for (plaintext in listOf(empty, large)) {
            val blob = AeadCipher.encrypt(dek, "blob-id", 1, plaintext)
            assertThat(AeadCipher.decrypt(dek, "blob-id", blob)).isEqualTo(plaintext)
        }
    }

    @Test
    fun `nonce is fresh and random on every encryption`() {
        val dek = randomDek()
        val nonces = (1..10_000).map {
            AeadCipher.encrypt(dek, "txn-2026-07", 1, "x".toByteArray()).nonce.toList()
        }
        assertThat(nonces.toSet()).hasSize(10_000)
        assertThat(nonces.first()).hasSize(AeadCipher.NONCE_LENGTH_BYTES)
    }

    @Test
    fun `tampered ciphertext fails GCM authentication`() {
        val dek = randomDek()
        val blob = AeadCipher.encrypt(dek, "txn-2026-07", 1, "sensitive data".toByteArray())
        val flippedCiphertext = blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val tampered = blob.copy(ciphertext = flippedCiphertext)

        assertThrows<GeneralSecurityException> {
            AeadCipher.decrypt(dek, "txn-2026-07", tampered)
        }
    }

    @Test
    fun `tampered tag fails GCM authentication`() {
        val dek = randomDek()
        val blob = AeadCipher.encrypt(dek, "txn-2026-07", 1, "sensitive data".toByteArray())
        val tampered = blob.copy(tag = blob.tag.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() })

        assertThrows<GeneralSecurityException> {
            AeadCipher.decrypt(dek, "txn-2026-07", tampered)
        }
    }

    @Test
    fun `wrong blobId fails via AAD binding`() {
        val dek = randomDek()
        val blob = AeadCipher.encrypt(dek, "txn-2026-07", 1, "sensitive data".toByteArray())

        assertThrows<GeneralSecurityException> {
            AeadCipher.decrypt(dek, "txn-2026-08", blob)
        }
    }

    @Test
    fun `wrong schema version fails via AAD binding`() {
        val dek = randomDek()
        val blob = AeadCipher.encrypt(dek, "txn-2026-07", 1, "sensitive data".toByteArray())
        val relabeled = blob.copy(schemaVersion = 2)

        assertThrows<GeneralSecurityException> {
            AeadCipher.decrypt(dek, "txn-2026-07", relabeled)
        }
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val blob = AeadCipher.encrypt(randomDek(), "txn-2026-07", 1, "sensitive data".toByteArray())

        assertThrows<GeneralSecurityException> {
            AeadCipher.decrypt(randomDek(), "txn-2026-07", blob)
        }
    }
}
