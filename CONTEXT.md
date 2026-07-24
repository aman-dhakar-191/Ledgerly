# CONTEXT.md — Ledgerly

**Read this file at the start of every session. It overrides convenience.**

## Identity

| | |
|---|---|
| App name | Ledgerly |
| Package | `com.amandhakar.ledgerly` |
| Room database | `ledgerly.db` |
| Keystore alias | `ledgerly_kwk` |
| Firebase project | `ledgerly-<unique>` (Phase 5 only) |

These are fixed. The Keystore alias and database name must not change after any
encrypted data exists — renaming either requires re-deriving the master key from
the passphrase and re-encrypting every blob.

---

Personal finance manager for Android. Single user. Reads bank/card SMS, maintains
a ledger, budgets, investments, and net worth. Local-first, end-to-end encrypted
cloud backup.

---

## Stack

- Kotlin, Jetpack Compose, Material 3
- Room (SQLite) — source of truth
- WorkManager — all background processing
- Hilt — DI
- Firebase Auth + Firestore — **Phase 5 only**, free Spark tier. Do not add the
  Firebase plugin, `google-services.json`, or any Firebase dependency before
  Phase 5. No GCP services, no Cloud Functions, no paid tier.
- Tink or AndroidX Security Crypto for AES-GCM; Argon2id via a vetted JVM binding
- kotlinx.serialization
- Testing: JUnit5, Robolectric, Turbine, Room in-memory

---

## Modules

```
:app                  Compose UI, DI wiring
:core:model           Paise, entities, blob serializer   [pure Kotlin]
:core:crypto-engine   Argon2id, HKDF, AES-GCM            [pure Kotlin]
:core:parser          Generic extractor, rule engine     [pure Kotlin]
:core:crypto          Keystore, BiometricPrompt          [Android]
:core:database        Room, DAOs, migrations             [Android]
```

Pure-Kotlin modules are unit-testable in CI. Keep security primitives there —
see `docs/crypto.md` for why the crypto split must not be merged.

---

## Layer model

Each layer may depend only on layers below it. No upward dependencies.

```
Insight        NetWorth, CashFlow, Runway, TaxSummary
Liabilities    Loan, CreditCard, AmortSchedule
Assets         Instrument, Holding, BalanceSnapshot
Planning       Budget, ScheduleRule, Goal, SinkingFund
Ledger         Account, Transaction, Transfer, Category
Ingest         SMS -> parse -> dedupe -> classify
```

Budgets read from Ledger. Budgets never read raw SMS.

---

## Invariants

These are not preferences. Violating any of them is a bug.

1. **Money is `Long` paise.** Never `Double`, never `Float`, never `BigDecimal` in
   storage. Format for display only, at the UI edge.

2. **Raw SMS is immutable and permanent.** Stored verbatim in its own table,
   separate from parsed output. Never edited, never deleted. Parser fixes are
   backfilled by re-running over this archive.

3. **No silent writes from non-deterministic sources.** Generic parser output and
   any LLM output land as `PENDING_REVIEW` suggestions. Only a matched
   `ParserRule` may write to the ledger autonomously. The user confirms
   everything else.

4. **Every parse must reconcile.** If the SMS carries a balance, computed balance
   must match reported balance. Mismatch = reject the parse and route to review,
   regardless of which parser produced it.

5. **Soft delete only.** `deleted_at` nullable timestamp. Never `DELETE FROM`.
   Every user edit to a transaction writes an audit row.

6. **Card spend is an expense. Card bill payment is a transfer.** Never both as
   expenses. Same rule for any account-to-account movement.

7. **Idempotent ingest.** Dedupe key = SHA-256 of (sender + timestamp + body).
   Re-scanning the inbox must never create duplicates.

8. **No plaintext financial data leaves the device.** Firestore receives
   ciphertext blobs plus non-sensitive sync metadata only. See `docs/crypto.md`
   for the exact allowlist of plaintext fields.

9. **Schema changes require a tested Room migration.** No destructive fallback.

10. **Offline-first.** Every feature works with no network. Sync is additive.

11. **The release signing keystore must never change.** Android treats a
    differently signed APK as a different app: update fails, and uninstalling to
    fix it destroys app-private storage including the Keystore-wrapped master
    key. Rotation is effectively impossible. See `docs/signing.md`.

12. **No financial data in notifications.** Amounts, balances, merchants and
    account numbers never appear in a notification, at any priority. Lock-screen
    previews are visible to anyone holding the phone. Notifications may say that
    something needs review; they may not say what it was.

---

## Distribution

Sideloaded via GitHub Releases, built by GitHub Actions, updated by an in-app
worker. Not Play Store. See `docs/ci.md`.

Unit tests gate every build. Device-only tests (Keystore hardware backing,
biometrics, Argon2id timing) cannot run in CI and must be verified manually on a
real phone — a green CI badge does not mean Phase 0 passed.

---

## Ask before doing

Stop and ask the user before:

- Changing the schema in `docs/schema.md`
- Adding any new dependency
- Touching anything in `docs/crypto.md`
- Adding a network call
- Changing the blob format
- Starting work on a phase that is not the current phase

---

## Current phase

**Phase 1 — Ledger that works.** See `tasks/phase-1.md`.

Phase 0 complete: all CI-verifiable criteria green. Device-only items (Keystore
hardware backing, biometric flows, Argon2id timing on-phone) still outstanding —
verify on a real device but they do not block Phase 1.

Out of scope right now: transfers, card liability, categories, budgets,
investments, Firestore sync, LLM anything.

---

## Docs

| File | Contents |
|---|---|
| `docs/schema.md` | Entities, fields, relationships |
| `docs/crypto.md` | Key hierarchy, blob format, biometric unlock |
| `docs/parser.md` | Rule format, learning flow, classification |
| `docs/corpus-findings.md` | Real SMS formats and what they break |
| `docs/ci.md` | GitHub Actions, signing, in-app updates |
| `docs/signing.md` | Keystore generation, backup, rotation limits |
| `docs/phases.md` | Full roadmap |

---

## Working agreement

- Small commits, one concern each
- Tests alongside code, not after
- If a requirement here conflicts with something that seems more convenient,
  the requirement wins — raise it rather than working around it
- When a phase completes, update the "Current phase" section above
