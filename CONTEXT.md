# CONTEXT.md — Ledgerly

**Read this file at the start of every session. It overrides convenience.**

## Identity

| | |
|---|---|
| App name | Ledgerly |
| Package | `com.<owner>.ledgerly` |
| Room database | `ledgerly.db` |
| Keystore alias | `ledgerly_kwk` |
| Firebase project | `ledgerly-<unique>` |

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
- Firebase Auth (identity only) + Firestore (encrypted blob storage only)
- Tink or AndroidX Security Crypto for AES-GCM; Argon2id via a vetted JVM binding
- kotlinx.serialization
- Testing: JUnit5, Robolectric, Turbine, Room in-memory

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

**Phase 0 — Foundation.** See `tasks/phase-0.md`.

Out of scope right now: UI beyond what tests need, SMS receiving, budgets,
investments, sync, LLM anything.

---

## Docs

| File | Contents |
|---|---|
| `docs/schema.md` | Entities, fields, relationships |
| `docs/crypto.md` | Key hierarchy, blob format, biometric unlock |
| `docs/parser.md` | Rule format, learning flow, classification |
| `docs/phases.md` | Full roadmap |

---

## Working agreement

- Small commits, one concern each
- Tests alongside code, not after
- If a requirement here conflicts with something that seems more convenient,
  the requirement wins — raise it rather than working around it
- When a phase completes, update the "Current phase" section above
