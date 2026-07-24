package com.amandhakar.ledgerly.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 per-blob Data Encryption Key derivation from the Master Key, per
 * docs/crypto.md: `DEK = HKDF-SHA256(MK, info = blobId)`.
 */
object Hkdf {
    const val OUTPUT_LENGTH_BYTES = 32

    fun deriveDek(masterKey: ByteArray, blobId: String): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(masterKey, null, blobId.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(OUTPUT_LENGTH_BYTES)
        generator.generateBytes(output, 0, output.size)
        return output
    }
}
