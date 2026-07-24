package com.amandhakar.ledgerly.crypto.android

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the (non-secret) Argon2 salt and the Keystore-wrapped Master Key. This is deliberately
 * *not* where the unwrapped MK lives — that only ever exists as a [ByteArray] in memory, per
 * docs/crypto.md.
 */
@Singleton
class SecureStore @Inject constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ledgerly_crypto_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var salt: ByteArray?
        get() = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = prefs.edit().putString(KEY_SALT, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    var wrappedMasterKey: WrappedMasterKey?
        get() {
            val nonce = prefs.getString(KEY_WRAPPED_NONCE, null) ?: return null
            val ciphertext = prefs.getString(KEY_WRAPPED_CIPHERTEXT, null) ?: return null
            return WrappedMasterKey(
                nonce = Base64.decode(nonce, Base64.NO_WRAP),
                ciphertextAndTag = Base64.decode(ciphertext, Base64.NO_WRAP),
            )
        }
        set(value) = prefs.edit()
            .putString(KEY_WRAPPED_NONCE, value?.nonce?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .putString(KEY_WRAPPED_CIPHERTEXT, value?.ciphertextAndTag?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .apply()

    var lastPassphraseVerifiedAtMillis: Long
        get() = prefs.getLong(KEY_LAST_PASSPHRASE_VERIFIED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PASSPHRASE_VERIFIED_AT, value).apply()

    /**
     * SHA-256(MK), not the MK itself — lets [unlockWithPassphrase][CryptoManagerImpl] confirm a
     * re-derived key is correct without ever needing the Keystore (and therefore biometric auth)
     * just to check a passphrase.
     */
    var masterKeyVerifier: ByteArray?
        get() = prefs.getString(KEY_MK_VERIFIER, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = prefs.edit().putString(KEY_MK_VERIFIER, value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    fun clearWrappedMasterKey() {
        prefs.edit()
            .remove(KEY_WRAPPED_NONCE)
            .remove(KEY_WRAPPED_CIPHERTEXT)
            .apply()
    }

    fun isSetUp(): Boolean = salt != null && wrappedMasterKey != null

    private companion object {
        const val KEY_SALT = "argon2_salt"
        const val KEY_WRAPPED_NONCE = "wrapped_mk_nonce"
        const val KEY_WRAPPED_CIPHERTEXT = "wrapped_mk_ciphertext"
        const val KEY_LAST_PASSPHRASE_VERIFIED_AT = "last_passphrase_verified_at"
        const val KEY_MK_VERIFIER = "mk_verifier"
    }
}

data class WrappedMasterKey(val nonce: ByteArray, val ciphertextAndTag: ByteArray)
