package com.amandhakar.ledgerly.crypto.android

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.amandhakar.ledgerly.crypto.AeadCipher
import com.amandhakar.ledgerly.crypto.Argon2Kdf
import com.amandhakar.ledgerly.crypto.EncryptedBlob
import com.amandhakar.ledgerly.crypto.Hkdf
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CURRENT_SCHEMA_VERSION = 1
private val PASSPHRASE_REPROMPT_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000

@Singleton
class CryptoManagerImpl @Inject constructor(
    private val secureStore: SecureStore,
    private val foregroundActivityHolder: ForegroundActivityHolder,
) : CryptoManager, DefaultLifecycleObserver {

    /** The only place the unwrapped Master Key ever lives: in memory, for this process's session. */
    private var masterKey: ByteArray? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) = lock()

    override fun isUnlocked(): Boolean = masterKey != null

    override fun lock() {
        masterKey?.fill(0)
        masterKey = null
    }

    override suspend fun setupPassphrase(passphrase: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        val activity = foregroundActivityHolder.current()
            ?: return@withContext Result.failure(IllegalStateException("No foreground activity to authenticate the KWK wrap"))

        runCatching {
            val salt = ByteArray(Argon2Kdf.SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
            val mk = Argon2Kdf.deriveMasterKey(passphrase, salt)

            KwkKeystoreManager.ensureKeyExists()
            val wrapCipher = KwkKeystoreManager.unauthenticatedWrapCipher()
            val authenticatedCipher = BiometricAuthenticator.authenticate(activity, wrapCipher, "Protect your passphrase")

            val wrapResult = KwkKeystoreManager.runCatchingInvalidation {
                authenticatedCipher.doFinal(mk)
            }.getOrThrow()

            secureStore.salt = salt
            secureStore.wrappedMasterKey = WrappedMasterKey(nonce = authenticatedCipher.iv, ciphertextAndTag = wrapResult)
            secureStore.masterKeyVerifier = sha256(mk)
            secureStore.lastPassphraseVerifiedAtMillis = System.currentTimeMillis()

            masterKey = mk
        }
    }

    override suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit> = withContext(Dispatchers.Default) {
        if (!secureStore.isSetUp()) return@withContext Result.failure(NotSetUpException())
        if (!KwkKeystoreManager.keyExists()) return@withContext Result.failure(KeyInvalidatedException())

        val staleMillis = System.currentTimeMillis() - secureStore.lastPassphraseVerifiedAtMillis
        if (staleMillis > PASSPHRASE_REPROMPT_INTERVAL_MILLIS) {
            return@withContext Result.failure(PassphraseReentryRequiredException())
        }

        val wrapped = secureStore.wrappedMasterKey ?: return@withContext Result.failure(NotSetUpException())

        runCatching {
            val unwrapCipher = KwkKeystoreManager.unauthenticatedUnwrapCipher(wrapped.nonce)
            val authenticatedCipher = BiometricAuthenticator.authenticate(activity, unwrapCipher, "Unlock Ledgerly")

            val mk = KwkKeystoreManager.runCatchingInvalidation {
                authenticatedCipher.doFinal(wrapped.ciphertextAndTag)
            }.getOrThrow()

            masterKey = mk
        }
    }

    override suspend fun unlockWithPassphrase(passphrase: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        if (!secureStore.isSetUp()) return@withContext Result.failure(NotSetUpException())
        val salt = secureStore.salt ?: return@withContext Result.failure(NotSetUpException())
        val verifier = secureStore.masterKeyVerifier ?: return@withContext Result.failure(NotSetUpException())

        val candidate = Argon2Kdf.deriveMasterKey(passphrase, salt)
        if (!MessageDigest.isEqual(sha256(candidate), verifier)) {
            candidate.fill(0)
            return@withContext Result.failure(InvalidPassphraseException())
        }

        masterKey = candidate
        secureStore.lastPassphraseVerifiedAtMillis = System.currentTimeMillis()
        refreshKwkWrap(candidate)
        Result.success(Unit)
    }

    /** Best-effort: re-wrap the MK via biometric so the convenience path stays current. Failure here doesn't fail the unlock. */
    private suspend fun refreshKwkWrap(mk: ByteArray) {
        val activity = foregroundActivityHolder.current() ?: return
        runCatching {
            KwkKeystoreManager.ensureKeyExists()
            val wrapCipher = KwkKeystoreManager.unauthenticatedWrapCipher()
            val authenticatedCipher = BiometricAuthenticator.authenticate(activity, wrapCipher, "Re-secure your passphrase")
            val wrapResult = authenticatedCipher.doFinal(mk)
            secureStore.wrappedMasterKey = WrappedMasterKey(nonce = authenticatedCipher.iv, ciphertextAndTag = wrapResult)
        }
    }

    override suspend fun encrypt(blobId: String, plaintext: ByteArray): EncryptedBlob {
        val mk = masterKey ?: throw IllegalStateException("locked")
        val dek = Hkdf.deriveDek(mk, blobId)
        return AeadCipher.encrypt(dek, blobId, CURRENT_SCHEMA_VERSION, plaintext)
    }

    override suspend fun decrypt(blobId: String, blob: EncryptedBlob): ByteArray {
        val mk = masterKey ?: throw IllegalStateException("locked")
        val dek = Hkdf.deriveDek(mk, blobId)
        return AeadCipher.decrypt(dek, blobId, blob)
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

class InvalidPassphraseException : Exception("Passphrase does not match the stored master key")
