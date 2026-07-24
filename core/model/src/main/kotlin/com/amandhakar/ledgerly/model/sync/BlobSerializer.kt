package com.amandhakar.ledgerly.model.sync

/**
 * kotlinx.serialization -> JSON -> gzip, per tasks/phase-0.md Task 0.6. `schemaVersion` is
 * deliberately not part of this contract: it lives outside the ciphertext as an unencrypted
 * field alongside the produced bytes (see [com.amandhakar.ledgerly.crypto.EncryptedBlob]), not
 * inside the serialized payload itself.
 */
interface BlobSerializer {
    suspend fun serialize(blobId: String, entities: List<Any>): ByteArray
    suspend fun deserialize(blobId: String, bytes: ByteArray): List<Any>
}
