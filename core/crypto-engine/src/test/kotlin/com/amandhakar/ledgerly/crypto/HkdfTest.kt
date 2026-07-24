package com.amandhakar.ledgerly.crypto

import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import org.junit.jupiter.api.Test

class HkdfTest {

    private fun randomMasterKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `same master key and blobId derive identical DEK`() {
        val mk = randomMasterKey()
        assertThat(Hkdf.deriveDek(mk, "txn-2026-07")).isEqualTo(Hkdf.deriveDek(mk, "txn-2026-07"))
    }

    @Test
    fun `different blobIds derive different DEKs from the same master key`() {
        val mk = randomMasterKey()
        val dek1 = Hkdf.deriveDek(mk, "txn-2026-07")
        val dek2 = Hkdf.deriveDek(mk, "txn-2026-08")
        assertThat(dek1).isNotEqualTo(dek2)
    }

    @Test
    fun `different master keys derive different DEKs for the same blobId`() {
        val dek1 = Hkdf.deriveDek(randomMasterKey(), "txn-2026-07")
        val dek2 = Hkdf.deriveDek(randomMasterKey(), "txn-2026-07")
        assertThat(dek1).isNotEqualTo(dek2)
    }

    @Test
    fun `DEK is 32 bytes for AES-256`() {
        assertThat(Hkdf.deriveDek(randomMasterKey(), "accounts-current")).hasLength(32)
    }
}
