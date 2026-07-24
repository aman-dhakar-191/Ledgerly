package com.amandhakar.ledgerly.crypto

/** Mirrors the Firestore document shape in docs/crypto.md (nonce/ciphertext/tag stored separately). */
data class EncryptedBlob(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
    val schemaVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBlob) return false
        return nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            tag.contentEquals(other.tag) &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + schemaVersion
        return result
    }
}
