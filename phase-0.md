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
- Module structure: `:app`, `:core:model`, `:core:crypto-engine`,
  `:core:crypto`, `:core:database` — see `CONTEXT.md`. `:core:crypto-engine`
  must have no Android dependency.
- `android:allowBackup="false"`, `android:fullBackupContent="false"`
- Detekt or ktlint
- **No Firebase.** Not in Gradle, not `google-services.json`, nothing. Phase 5.
- In `app/build.gradle.kts` declare, at top level so CI can grep them:
  ```kotlin
  val appVersionName = "0.1.0"
  val appVersionCode = 1
  ```
- Add `.github/workflows/build.yml` from `docs/ci.md`

**Done when:** empty app builds and installs; `./gradlew test` runs green in CI.

---

## Task 0.2 — Money type

```kotlin
@JvmInline
value class Paise(val value: Long) {
    operator fun plus(other: Paise): Paise
    operator fun minus(other: Paise): Paise
    fun format(currency: String = "INR"): String
    companion object {
        fun fromRupeeString(s: String): Paise?
    }
}
```

`fromRupeeString` must handle every form observed in the real corpus
(`docs/corpus-findings.md`):

| Input | Note |
|---|---|
| `1,234.56` | commas |
| `Rs.500` | prefix, no space |
| `Rs 1698` | prefix with space, no decimals |
| `Rs656.7` | **no space, one decimal** (axio) |
| `INR 2,170.00` | |
| `50.0` / `2` / `210.25` | 0, 1 or 2 decimals (SBI UPI) |
| `.00` / `.30` | bare leading decimal (ICICI statement) |
| `7,050/-` | trailing `/-` (EPFO) |
| `USD 23.60` | non-INR — caller handles currency |

Malformed input returns null. No `Double` anywhere in the money path. Room
`TypeConverter` to `Long`.

**Tests:** every row in the table above; malformed input returns null;
arithmetic; formatting.

> **Added after Phase 0 completed.** The original spec predated the corpus
> analysis and listed only two formats. If `Paise` is already implemented,
> extend `fromRupeeString` and its tests to cover the full table — several of
> these (no-space prefix, bare leading decimal, trailing `/-`) will not parse
> under a naive implementation.

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

Split by where each item can actually be verified. An item is not done until
checked in its own column — code written to spec but never compiled is not done.

**Verifiable in CI (`./gradlew test`)**
- [ ] `:core:crypto-engine` tests green — KDF determinism, wrong passphrase
      fails, round-trip, 10,000 distinct nonces, tampered ciphertext/tag/blobId/
      schemaVersion all fail closed
- [ ] `Paise` tests green; no `Double` anywhere in the money path
- [ ] Blob serializer round-trips at 1 / 100 / 10,000 entities; 900 KiB split and
      reassemble; unknown fields forward-compatible
- [ ] detekt clean
- [ ] **Whole project compiles.** Room, Hilt, Compose included.
- [ ] `schemas/1.json` generated by KSP and committed
- [ ] Room migration test v1 -> v2 passes
- [ ] Soft delete verified across all DAOs; no `@Delete` anywhere

**Device only (`./gradlew connectedAndroidTest` on a real phone)**
- [ ] Keystore key is hardware-backed; StrongBox attempted, TEE fallback works
- [ ] `BiometricPrompt` unlock succeeds; device-credential fallback works
- [ ] `KeyPermanentlyInvalidatedException` path prompts for passphrase rather
      than failing open
- [ ] App locks on background, requires biometric on resume
- [ ] Argon2id timing measured **on the phone**. Sandbox or CI timings do not
      count. If >1.5s, report before adjusting parameters.

Report against both columns before Phase 1 is discussed.
