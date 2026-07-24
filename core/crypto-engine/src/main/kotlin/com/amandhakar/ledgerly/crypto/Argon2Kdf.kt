package com.amandhakar.ledgerly.crypto

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id key derivation, per docs/crypto.md: 64 MiB memory, t=3, p=4, 32-byte
 * output. Pure-Java (BouncyCastle) rather than a native binding — this is the
 * "vetted JVM binding" CONTEXT.md calls for, and it runs identically on the JVM
 * and on Android, so it can be exercised by real unit tests instead of only on
 * a device.
 *
 * BouncyCastle's implementation is single-threaded regardless of the
 * parallelism parameter, so timing here is a lower bound, not a substitute for
 * the on-device benchmark phase-0.md requires.
 */
object Argon2Kdf {
    const val MEMORY_KIB = 64 * 1024
    const val ITERATIONS = 3
    const val PARALLELISM = 4
    const val OUTPUT_LENGTH_BYTES = 32
    const val SALT_LENGTH_BYTES = 16

    fun deriveMasterKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH_BYTES) { "salt must be $SALT_LENGTH_BYTES bytes" }

        val passwordBytes = charsToUtf8Bytes(passphrase)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KIB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator().apply { init(params) }
            val output = ByteArray(OUTPUT_LENGTH_BYTES)
            generator.generateBytes(passwordBytes, output)
            return output
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
