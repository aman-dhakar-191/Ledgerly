package com.amandhakar.ledgerly.crypto.android

import androidx.fragment.app.FragmentActivity
import com.amandhakar.ledgerly.crypto.EncryptedBlob

/** See docs/crypto.md for the full key hierarchy and unlock flow this implements. */
interface CryptoManager {
    suspend fun setupPassphrase(passphrase: CharArray): Result<Unit>
    suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit>
    suspend fun unlockWithPassphrase(passphrase: CharArray): Result<Unit>
    fun isUnlocked(): Boolean
    fun lock()
    suspend fun encrypt(blobId: String, plaintext: ByteArray): EncryptedBlob
    suspend fun decrypt(blobId: String, blob: EncryptedBlob): ByteArray
}

/** The Keystore-wrapped Master Key was invalidated (e.g. new biometric enrolled) and must be re-derived from the passphrase. */
class KeyInvalidatedException(cause: Throwable? = null) : Exception(
    "Keystore key was invalidated; passphrase required to re-derive the master key",
    cause,
)

/** Biometric unlock refused because the 30-day passphrase re-prompt window has elapsed. */
class PassphraseReentryRequiredException : Exception("Passphrase re-entry required (30-day window elapsed)")

/** No passphrase has been set up yet; call [CryptoManager.setupPassphrase] first. */
class NotSetUpException : Exception("CryptoManager has not been set up with a passphrase yet")
