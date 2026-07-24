package com.amandhakar.ledgerly.update

import java.security.MessageDigest

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * tasks/update-system.md's signature check, minus the `PackageManager` calls: the Android layer
 * extracts each signer's raw certificate bytes (`Signature.toByteArray()`, both the downloaded
 * APK's and the running app's own) and hands them here. A mismatch — or either side being
 * unreadable/empty — must never be treated as a pass.
 */
fun verifyMatchingSignatures(remoteCertBytes: List<ByteArray>, localCertBytes: List<ByteArray>): Boolean {
    if (remoteCertBytes.isEmpty() || localCertBytes.isEmpty()) return false
    val remote = remoteCertBytes.map(::sha256Hex).toSet()
    val local = localCertBytes.map(::sha256Hex).toSet()
    return remote == local
}
