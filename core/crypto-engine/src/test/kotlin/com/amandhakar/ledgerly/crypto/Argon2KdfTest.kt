package com.amandhakar.ledgerly.crypto

import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

class Argon2KdfTest {

    private fun randomSalt(): ByteArray = ByteArray(Argon2Kdf.SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    @Test
    fun `same passphrase and salt derive identical master key`() {
        val salt = randomSalt()
        val mk1 = Argon2Kdf.deriveMasterKey("correct horse battery staple".toCharArray(), salt)
        val mk2 = Argon2Kdf.deriveMasterKey("correct horse battery staple".toCharArray(), salt)
        assertThat(mk1).isEqualTo(mk2)
        assertThat(mk1).hasLength(Argon2Kdf.OUTPUT_LENGTH_BYTES)
    }

    @Test
    fun `wrong passphrase derives a different master key`() {
        val salt = randomSalt()
        val correct = Argon2Kdf.deriveMasterKey("correct horse battery staple".toCharArray(), salt)
        val wrong = Argon2Kdf.deriveMasterKey("Correct Horse Battery Staple".toCharArray(), salt)
        assertThat(correct).isNotEqualTo(wrong)
    }

    @Test
    fun `different salt derives a different master key for the same passphrase`() {
        val mk1 = Argon2Kdf.deriveMasterKey("same passphrase".toCharArray(), randomSalt())
        val mk2 = Argon2Kdf.deriveMasterKey("same passphrase".toCharArray(), randomSalt())
        assertThat(mk1).isNotEqualTo(mk2)
    }

    @Test
    fun `benchmark - reports derivation timing on this machine`() {
        val salt = randomSalt()
        val elapsedMs = measureTimeMillis {
            Argon2Kdf.deriveMasterKey("benchmark passphrase".toCharArray(), salt)
        }
        println(
            "Argon2Kdf benchmark (this sandbox JVM, NOT a real Android device): " +
                "${elapsedMs}ms for memory=${Argon2Kdf.MEMORY_KIB}KiB t=${Argon2Kdf.ITERATIONS} " +
                "p=${Argon2Kdf.PARALLELISM}. Required: measure on a real device before shipping; " +
                "if >1500ms there, reduce iterations before reducing memory.",
        )
        // Sanity bound only - not the product requirement, which is a real-device measurement.
        assertThat(elapsedMs).isLessThan(30_000L)
    }
}
