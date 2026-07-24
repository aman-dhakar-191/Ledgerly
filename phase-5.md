# Phase 5 — Sync

Read `CONTEXT.md` and **`docs/crypto.md` in full** before starting. The blob
format, key hierarchy and conflict rules are specified there and are not
negotiable.

> **Dependency note.** This is the first phase that adds Firebase. Everything
> before it runs entirely offline. If sync proves troublesome, the app still
> works without it — do not let sync problems block later phases.
>
> `docs/crypto.md` was written in Phase 0 and its blob format has been
> round-trip tested, but never against Firestore. Expect friction at the
> integration boundary, not in the crypto itself.

**Goal:** the ledger survives losing the device.

**In scope:** Firebase Auth, Firestore blob storage, upload and merge, restore,
device-only Phase 0 verification.

**Out of scope:** multi-user, sharing, real-time collaboration. This is backup
with multi-device convenience, not a collaborative system.

---

## Phase 4 gate

- [ ] Portfolio values verified against real statements

---

## Task 5.1 — Firebase setup

**Free Spark tier only.** No GCP services, no Cloud Functions, no paid features.
One user syncing monthly encrypted blobs generates a handful of writes per day —
nowhere near free-tier limits.

- Firebase Auth, email/password. **Identity only** — it has no relationship to
  the master key.
- Firestore with the security rules from `docs/crypto.md`
- `google-services.json` via the CI secret, with the placeholder fallback
  pattern from `docs/ci.md`

**Tests:** security rules deny cross-user reads (test with two accounts); the
build succeeds without the secret present.

---

## Task 5.2 — Complete Phase 0's device verification

Still outstanding from Phase 0 and now blocking, because sync makes the
passphrase the sole recovery path:

- [ ] Keystore key is hardware-backed (StrongBox attempted, TEE fallback works)
- [ ] `BiometricPrompt` unlock succeeds; device-credential fallback works
- [ ] `KeyPermanentlyInvalidatedException` prompts for passphrase, does not fail
      open
- [ ] App locks on background, requires biometric on resume
- [ ] Argon2id timing measured **on the phone**

Also re-enable `FLAG_SECURE` here if it hasn't been already — see `CONTEXT.md`.

---

## Task 5.3 — Upload

Per `docs/crypto.md`.

```
local write → mark blob dirty → WorkManager (5-min debounce)
            → serialize → gzip → encrypt (fresh nonce) → write with updatedAt precondition
```

- Monthly blobs per entity type
- **Fresh `SecureRandom` nonce on every encryption.** Never derive from a
  counter, timestamp or content hash.
- AAD binds ciphertext to `blobId || schemaVersion`
- Blob approaching 900 KiB → split to `-a`, `-b`

**Plaintext allowlist is exactly:** `userId`, `blobId`, `schemaVersion`,
`updatedAt`, `deviceId`, `nonce`, `tag`. Nothing else leaves the device
unencrypted. A single leaked field here defeats the entire design.

**Tests:** 10,000 uploads produce 10,000 distinct nonces; no plaintext financial
field appears in any Firestore document (assert on the serialized payload);
split triggers at the size threshold.

---

## Task 5.4 — Merge on conflict

**Last-writer-wins is forbidden** — it silently drops transactions.

On `updatedAt` precondition failure:
1. Pull remote blob, decrypt
2. Merge by entity `id` — transaction IDs are idempotent from the dedupe hash
3. Field-level conflict → higher `updated_at` wins, log to audit
4. Re-encrypt with a **fresh nonce**, push

Merging is safe because transactions are append-mostly. User edits are the only
real conflict surface, and they're rare on a single-user app.

**Tests:** entities present on only one side survive the merge; a field conflict
resolves by timestamp and writes an audit row; concurrent writes from two devices
lose nothing.

---

## Task 5.5 — Restore

The reason this phase exists.

```
install → sign in → enter passphrase → derive MK → pull all blobs
        → decrypt → populate Room → verify integrity
```

- Progress UI; restore of a year's data is not instant
- Verify entity counts and reconcile balances after restore
- Partial failure must be resumable, never leaving a half-populated database

**Wrong passphrase must fail cleanly** with a clear message. This is where a
user discovers they've lost their data; the message should be unambiguous rather
than a decryption stack trace.

**Tests:** full round trip — wipe app data, reinstall, restore, verify every
entity count and balance matches. This is the Phase 5 exit criterion.

---

## Task 5.6 — Sync status and control

- Last-synced timestamp, visible
- Manual "sync now"
- Pending-upload count
- Clear error states; sync failure never blocks local use
- Toggle to disable sync entirely

Exponential backoff, capped at 6 hours.

---

## Exit criteria

**CI**
- [ ] Nonce uniqueness across 10,000 encryptions
- [ ] No plaintext financial data in any serialized Firestore payload
- [ ] Merge preserves one-sided entities
- [ ] Security rules deny cross-user access

**Device**
- [ ] All Phase 0 device items now verified (Task 5.2)
- [ ] **Full restore test:** wipe, reinstall, restore from passphrase, verify
      complete ledger integrity
- [ ] Two-device sync loses no transactions

The restore test is the exit criterion. Everything else in this phase exists to
make it work.
