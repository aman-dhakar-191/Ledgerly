package com.amandhakar.ledgerly.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SignatureVerificationTest {

    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certB = byteArrayOf(5, 6, 7, 8)

    @Test
    fun `identical single signer matches`() {
        assertThat(verifyMatchingSignatures(listOf(certA), listOf(certA.copyOf()))).isTrue()
    }

    @Test
    fun `different signers never match`() {
        assertThat(verifyMatchingSignatures(listOf(certA), listOf(certB))).isFalse()
    }

    @Test
    fun `matching signer sets in different order still match`() {
        assertThat(verifyMatchingSignatures(listOf(certA, certB), listOf(certB, certA.copyOf()))).isTrue()
    }

    @Test
    fun `empty remote never matches`() {
        assertThat(verifyMatchingSignatures(emptyList(), listOf(certA))).isFalse()
    }

    @Test
    fun `empty local never matches`() {
        assertThat(verifyMatchingSignatures(listOf(certA), emptyList())).isFalse()
    }

    @Test
    fun `both empty is a refusal, not a vacuous pass`() {
        assertThat(verifyMatchingSignatures(emptyList(), emptyList())).isFalse()
    }
}
