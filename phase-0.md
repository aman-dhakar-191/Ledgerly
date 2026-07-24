# Phase 0 — Foundation

Read `CONTEXT.md`, `docs/schema.md`, and `docs/crypto.md` before starting.

**In scope:** crypto, Room schema, blob serialization, migrations, DI setup.
**Out of scope:** SMS, UI beyond a bare unlock screen, budgets, investments,
sync upload, anything LLM.

Do not add dependencies beyond those listed without asking.

---

## Task 0.1 — Project setup

- Android Studio project, Kotlin, min SDK 26, target latest stable
- Compose + Material 3
- Hilt
- Module structure: `:app`, `:core:crypto`, `:core:database`, `:core:model`
- `android:allowBackup="false"`, `android:fullBackupContent="false"`
- Detekt or ktlint

**Done when:** empty app builds and installs.

---

## Task 0.2 — Money type

```kotlin
@JvmInline
value class Paise(val value: Long) {
    operator fun plus(other: Paise): Paise
    operator fun minus(other: Paise): Paise
    fun format(currency: String = "INR"): String
    companion object {
        fun fromRupeeString(s: String): Paise?  // handles "1,234.56", "Rs.500"
    }
}
```

No `Double` anywhere in the money path. Room `TypeConverter` to `Long`.

**Tests:** parsing with commas, decimals, currency prefixes, missing decimals,
malformed input returns null; arithmetic; formatting.

---

## Task 0.3 — Entities

Implement Phase 0 and Phase 1 entities from `docs/schema.md`:
`RawSms`, `SenderRegistry`, `ParserRule`, `GoldenTest`, `Account`, `Transaction`,
`TransactionAudit`, `BalanceAnchor`.

> **Added after Phase 0 started.** If Task 0.3 is already complete,
> `BalanceAnchor` is a small additive change: new entity, new DAO, index on
> `(account_id, as_of)`. No existing entity changes. Add it and extend the
> Task 0.4 DAO tests.

Every entity: `id: String` (UUID), `created_at`, `updated_at`, `deleted_at: Long?`.

Do not implement Phase 2+ entities.

**Tests:** insert/query round-trip for each; unique index on
`RawSms.dedupe_hash` rejects duplicates.

---

## Task 0.4 — DAOs

Room DAOs with Flow-returning read queries. **Every read query must filter
`deleted_at IS NULL`.** Provide `softDelete(id)`; no `@Delete` on any DAO.

`TransactionDao` needs: by account + date range, by status, by
`raw_sms_id`, by `transfer_id`.

**Tests:** soft-deleted rows excluded from all reads; audit row written on update.

---

## Task 0.5 — CryptoManager

Follow `docs/crypto.md` exactly.

```kotlin
interface CryptoManager {
    suspend fun setupPassphrase(passphrase: CharArray): Result<Unit>
    suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit>
    suspend fun unlockWithPassphrase(passphrase: CharArray): Result<Unit>
    fun isUnlocked(): Boolean
    fun lock()
    suspend fun encrypt(blobId: String, plaintext: ByteArray): EncryptedBlob
    suspend fun decrypt(blobId: String, blob: EncryptedBlob): ByteArray
}

data class EncryptedBlob(
    val nonce: ByteArray, val ciphertext: ByteArray,
    val tag: ByteArray, val schemaVersion: Int
)
```

Requirements:
- Argon2id 64 MiB / t=3 / p=4 / 32 bytes
- KWK per the `KeyGenParameterSpec` in `docs/crypto.md`, StrongBox with TEE
  fallback
- Fresh `SecureRandom` 12-byte nonce on **every** encryption
- AAD = `blobId || schemaVersion`
- Zero the MK `ByteArray` on `lock()`; call `lock()` from `ProcessLifecycleOwner`
  `onStop`
- Handle `KeyPermanentlyInvalidatedException` -> clear wrapped MK, require
  passphrase

**Tests (all required, see `docs/crypto.md`):**
- same passphrase + salt -> identical MK
- wrong passphrase fails
- round-trip preserves bytes
- flipped ciphertext bit fails GCM auth
- wrong `blobId` fails via AAD
- 10,000 encryptions -> 10,000 distinct nonces
- `lock()` zeroes key material

Benchmark Argon2id on a real device. Report the timing; if >1.5s, ask before
adjusting parameters.

---

## Task 0.6 — Blob serializer

```kotlin
interface BlobSerializer {
    suspend fun serialize(blobId: String, entities: List<@Serializable Any>): ByteArray
    suspend fun deserialize(blobId: String, bytes: ByteArray): List<Any>
}
```

kotlinx.serialization -> JSON -> gzip. `schemaVersion` outside the ciphertext.
If serialized+compressed+encrypted size >900 KiB, split into `-a`, `-b` suffixed
blobs.

**Tests:** round-trip 1, 100, 10,000 transactions; split triggers correctly;
unknown fields in older blobs don't crash deserialization.

---

## Task 0.7 — Migrations

Room schema export on. `Migration(1,2)` as a real example (add a nullable column
to `Transaction`), with a test using `MigrationTestHelper`.

`fallbackToDestructiveMigration` is **forbidden**.

**Tests:** v1 -> v2 preserves all rows and values.

---

## Task 0.8 — Unlock screen

Minimal Compose UI, no design work:
- First run: passphrase setup with zxcvbn strength meter, min score 3, plus the
  verbatim recovery warning from `docs/crypto.md` with explicit acknowledgement
- Subsequent: `BiometricPrompt`, device credential fallback, passphrase link
- `FLAG_SECURE` on the window

**Done when:** cold start -> biometric -> unlocked; wrong passphrase rejected;
recovery warning cannot be skipped.

---

## Phase 0 exit criteria

- [ ] All crypto tests green
- [ ] Encrypt/decrypt round-trip preserves entities exactly
- [ ] Migration test v1 -> v2 passes
- [ ] Soft delete verified across all DAOs
- [ ] No `Double` in any money path
- [ ] Argon2id timing measured and reported
- [ ] App locks on background, requires biometric on resume

Report results against this checklist before Phase 1 is discussed.
