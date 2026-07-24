package com.amandhakar.ledgerly.crypto.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the Keystore Wrapping Key (KWK) exactly per docs/crypto.md: hardware-backed,
 * biometric+device-credential gated, StrongBox with TEE fallback, invalidated by new biometric
 * enrollment. The KWK never leaves the Keystore; it is only ever used, via [Cipher], to
 * encrypt/decrypt the Master Key bytes in place.
 */
internal object KwkKeystoreManager {
    const val KWK_ALIAS = "ledgerly_kwk"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128
    const val NONCE_LENGTH_BYTES = 12

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun keyExists(): Boolean = keyStore().containsAlias(KWK_ALIAS)

    fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(KWK_ALIAS)) ks.deleteEntry(KWK_ALIAS)
    }

    /** Generates the KWK if it does not already exist. Retries without StrongBox if unavailable. */
    @Suppress("SwallowedException") // intentional fallback path, not an error to surface
    fun ensureKeyExists() {
        if (keyExists()) return
        try {
            generate(useStrongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(useStrongBox = false)
        }
    }

    private fun generate(useStrongBox: Boolean) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KWK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(useStrongBox)

        setUserAuthenticationParametersCompat(specBuilder)

        keyGenerator.init(specBuilder.build())
        keyGenerator.generateKey()
    }

    /**
     * `setUserAuthenticationParameters(timeout, types)` requires API 30. On 26-29 the closest
     * equivalent is `setUserAuthenticationValidityDurationSeconds(-1)`, which also requires a
     * fresh biometric per use but does not support the device-credential fallback type
     * explicitly — [KeyGenParameterSpec.Builder.setUserAuthenticationRequired] alone still allows
     * device credential as a fallback on those versions via the system-provided confirmation UI.
     */
    private fun setUserAuthenticationParametersCompat(builder: KeyGenParameterSpec.Builder) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
    }

    private fun secretKey(): SecretKey = keyStore().getKey(KWK_ALIAS, null) as SecretKey

    /** A Cipher initialized for wrap (encrypt); must be authenticated via [BiometricAuthenticator] before use. */
    fun unauthenticatedWrapCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }

    /** A Cipher initialized for unwrap (decrypt) with the nonce that was used at wrap time. */
    fun unauthenticatedUnwrapCipher(nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        }

    /** Maps the Keystore's own invalidation signal to our domain exception. */
    fun <T> runCatchingInvalidation(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: KeyPermanentlyInvalidatedException) {
        deleteKey()
        Result.failure(KeyInvalidatedException(e))
    }
}
