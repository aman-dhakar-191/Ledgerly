# Crypto

**Nothing in this file changes without asking the user first.**

Threat model: lost or stolen device, and a curious or compelled cloud provider.
Not defended: malware on an unlocked device with the app open.

---

## Module split

Crypto lives in two modules, deliberately:

| Module | Contents | Tests |
|---|---|---|
| `:core:crypto-engine` | Argon2id KDF, HKDF, AES-256-GCM AEAD. **Pure Kotlin, zero Android dependency.** | JVM unit tests, run in CI on every commit |
| `:core:crypto` | `CryptoManagerImpl` — Keystore wrapping, `BiometricPrompt`, lifecycle locking | instrumented, device only |

The split exists so the security-critical primitives are testable in CI. Anything
touching Keystore forces instrumented tests, which GitHub runners cannot execute
meaningfully; keeping the primitives Android-free means nonce uniqueness,
tamper-detection, and KDF determinism are verified on every push.

Do not merge these modules or move primitive logic into `:core:crypto`.

---

## Key hierarchy

```
User passphrase
    |  Argon2id (64 MB, t=3, p=4, 32-byte output)
    v
Master Key (MK, 256-bit)
    |                              |
    | wrapped by                   | derives (HKDF-SHA256, per-blob info)
    v                              v
Keystore Wrapping Key (KWK)    Data Encryption Key (DEK) per blob
  hardware-backed                  AES-256-GCM
  biometric-gated
  non-exportable
```

**The passphrase never leaves the device.** It is not stored, not synced, not
recoverable. Firebase Auth provides identity only and has no relationship to MK.

### Argon2id parameters
- memory: 64 MiB
- iterations: 3
- parallelism: 4
- output: 32 bytes
- salt: 16 random bytes, generated once per user, stored plaintext in Firestore
  (salts are not secret) and mirrored locally

Benchmark on target device. If derivation exceeds ~1.5s, reduce iterations
before reducing memory — memory hardness is the point.

---

## Keystore configuration

```kotlin
KeyGenParameterSpec.Builder(KWK_ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setBlockModes(BLOCK_MODE_GCM)
    .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .setUserAuthenticationRequired(true)
    .setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)
    .setInvalidatedByBiometricEnrollment(true)
    .setIsStrongBoxBacked(true)   // fall back to TEE if unavailable
    .build()
```

`setInvalidatedByBiometricEnrollment(true)` is deliberate: enrolling a new
fingerprint destroys the wrapping key. Coercion into adding a finger yields
nothing. Cost is passphrase re-entry after a legitimate enrolment — acceptable.

Handle `StrongBoxUnavailableException` by retrying without StrongBox. Handle
`KeyPermanentlyInvalidatedException` by clearing the wrapped MK and prompting
for the passphrase.

---

## Unlock flow

**First run**
1. Firebase Auth sign-in
2. User sets passphrase (min 12 chars, zxcvbn score >= 3)
3. Generate salt, derive MK
4. Generate KWK in Keystore
5. Wrap MK with KWK, store wrapped blob in EncryptedSharedPreferences
6. Show the recovery warning (below) and require explicit acknowledgement

**Subsequent cold starts**
1. `BiometricPrompt` -> `CryptoObject` bound to KWK
2. Unwrap MK into memory
3. MK lives in a `ByteArray` for the session; zero it in `onStop`

**Recovery / re-prompt**
- Passphrase re-prompt every 30 days
- Passphrase required if Keystore key is invalidated
- Device credential (PIN/pattern) is an accepted biometric fallback
- Never fall back to unencrypted access

**Recovery warning text (must be shown verbatim at setup):**
> Your passphrase is the only way to recover your data. It cannot be reset — not
> by Google, not by this app, not by anyone. If you lose both your passphrase and
> this device, your backups are permanently unreadable. Write it down and store
> it somewhere physical.

---

## Blob format

Granularity: **one blob per entity type per month.**

```
users/{uid}/blobs/txn-2026-07
users/{uid}/blobs/holdings-2026-07
users/{uid}/blobs/accounts-current     // small, single blob
```

Rationale: a single blob rewrites all history on every insert; per-transaction
docs leak transaction counts and timing through metadata. Monthly means only the
current blob churns.

### Firestore document shape

```json
{
  "userId":       "...",     // plaintext, required for security rules
  "blobId":       "txn-2026-07",
  "schemaVersion": 1,
  "updatedAt":    1721800000000,
  "deviceId":     "uuid",
  "nonce":        "base64, 12 bytes",
  "ciphertext":   "base64",
  "tag":          "base64, 16 bytes"
}
```

**Plaintext allowlist — nothing else may appear outside `ciphertext`:**
`userId`, `blobId`, `schemaVersion`, `updatedAt`, `deviceId`, `nonce`, `tag`.

Known leakage: `blobId` reveals which months have activity; `updatedAt` reveals
sync timing. Accepted.

### Encryption
1. Serialize entities to JSON (kotlinx.serialization)
2. gzip
3. Derive DEK: `HKDF-SHA256(MK, info = blobId)`
4. AES-256-GCM, **fresh 12-byte random nonce every single encryption**
5. AAD = `blobId || schemaVersion` — binds ciphertext to its identity

**Nonce reuse under the same key breaks GCM catastrophically.** Never derive a
nonce from a counter, timestamp, or content hash. `SecureRandom` only.

`schemaVersion` sits outside the ciphertext so format migrations are possible
without decrypting first.

### Size
1 MiB Firestore document limit. Gzipped JSON gives roughly 3–5k transactions per
monthly blob. If a blob approaches 900 KiB, split to `txn-2026-07-a`,
`txn-2026-07-b`.

---

## Sync

Room is the source of truth. Firestore is a backup mirror.

**Upload:** local write -> mark blob dirty -> WorkManager, 5-minute debounce ->
serialize, gzip, encrypt -> write with `updatedAt` precondition.

**Conflict:** last-writer-wins is **forbidden** here — it silently drops
transactions. On precondition failure:
1. Pull remote blob, decrypt
2. Merge by entity `id` (transaction IDs are idempotent from the dedupe hash)
3. Field-level conflict -> higher `updated_at` wins; log to audit
4. Re-encrypt with a **fresh nonce**, push

Merging is safe because transactions are append-mostly.

**Failure handling:** exponential backoff, cap 6 hours. Sync failure never blocks
local use. Surface a "last synced" timestamp in settings.

---

## Additional hardening

- `FLAG_SECURE` on all windows containing financial data — blocks screenshots
  and the app-switcher thumbnail.
  **Currently disabled for development** (see `CONTEXT.md`). This is temporary
  and must be re-enabled before the app holds real data long-term; without it,
  the app-switcher preview and any screenshot expose balances and transactions.
- Disable Android auto-backup: `android:allowBackup="false"`,
  `android:fullBackupContent="false"`
- No financial values in logs, ever. Lint rule if practical.
- Root/debug detection is out of scope — it doesn't hold against a real attacker
  and costs usability

---

## Required tests

- Argon2id produces identical MK from same passphrase + salt
- Wrong passphrase fails to unwrap
- Encrypt/decrypt round-trip preserves entities exactly
- Tampered ciphertext fails GCM auth
- Tampered `blobId` fails via AAD binding
- 10,000 encryptions produce 10,000 distinct nonces
- Blob >1 MiB triggers split
- Merge preserves entities present on only one side
- Keystore invalidation path prompts for passphrase rather than failing open
