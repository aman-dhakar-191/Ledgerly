package com.amandhakar.ledgerly.crypto.android

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Wraps [BiometricPrompt] as a suspend call bound to a specific [Cipher] operation. */
object BiometricAuthenticator {

    suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String): Cipher =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher != null) {
                        continuation.resume(authenticatedCipher)
                    } else {
                        continuation.resumeWithException(IllegalStateException("No authenticated cipher returned"))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    continuation.resumeWithException(BiometricAuthException(errorCode, errString.toString()))
                }

                override fun onAuthenticationFailed() {
                    // A single failed attempt (e.g. unrecognized fingerprint); the prompt stays open
                    // for retries and will eventually call onAuthenticationError if abandoned.
                }
            }

            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()

            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}

class BiometricAuthException(val errorCode: Int, message: String) : Exception(message)
