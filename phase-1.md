# Phase 1 — Ledger that works

Read `CONTEXT.md`, `docs/schema.md`, `docs/parser.md`, and
**`docs/corpus-findings.md`** before starting. The corpus findings come from a
real 5,613-message inbox and override general assumptions elsewhere.

**Goal:** every real bank SMS either parses correctly or lands in the review
inbox. Zero silent wrong entries. Nothing downstream matters until this holds.

**In scope:** SMS ingest, sender normalisation and registry, non-transaction
pre-filter, generic extractor, rule learning and validation, review inbox,
accounts, balance anchors, self-transfer detection, initialization,
reconciliation, golden tests, data export, update system.

**Out of scope:** transfers, credit-card liability modelling, categories,
budgets, investments, Firestore sync, any LLM call. Those are Phases 2–6.

Do not add Firebase.

---

## Task 1.1 — SMS permissions and receiver

- `RECEIVE_SMS`, `READ_SMS` in the manifest; runtime request with a clear
  rationale screen
- `BroadcastReceiver` on `SMS_RECEIVED_ACTION`
- **The receiver does no parsing.** It writes `RawSms` and enqueues a
  `WorkManager` job. Parsing on the receiver thread will be killed by the system.
- Compute `dedupe_hash` = SHA-256(sender + received_at + body) before insert;
  the unique index makes re-ingest safe
- Graceful degradation: if permission is denied, the app still works with manual
  entry

**Tests:** duplicate SMS inserts once; receiver returns fast; missing permission
does not crash.

---

## Task 1.2 — Archive import

One-time bootstrap over the existing inbox via `Telephony.Sms.Inbox`.

- Import **everything**, as far back as the inbox goes. Raw text is cheap and it
  is the validation corpus for rule generation.
- Batch in chunks, show progress, survive rotation and backgrounding
- Idempotent — running twice imports nothing new
- No parsing during import. Store only.

**Tests:** 5,000-message import completes; re-run inserts zero rows.

---

## Task 1.3 — Update system

Per `tasks/update-system.md`.

**Build this early, not last.** Sideloaded builds are how every subsequent task
reaches the device. Without it, testing each of Tasks 1.4–1.17 means manual APK
transfer every time.

Requires the four signing secrets to be set (`docs/signing.md`) — until then CI
only produces debug APKs, which the updater ignores by design.

---

## Task 1.4 — Sender normalisation and registry

**Sender IDs identify the telecom route, not the bank.** The corpus has 13+
sender IDs all carrying identical ICICI formats. See `docs/corpus-findings.md`.

```kotlin
fun normalizeSender(raw: String): String =
    raw.replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")
```

- Group distinct senders by **institution**, ranked by message count
- User classifies each institution once: BANK, CARD, OTP, PROMO, SPAM, UNKNOWN
- Only `trusted = true` institutions are parsed
- `RawSms` keeps the raw sender for provenance; rule lookup uses institution

> **Retrofit available:** the TRAI message-type suffix (`-P`/`-S`/`-T`/`-G`) is
> a regulator-assigned signal worth capturing rather than stripping. Added after
> Phase 1 shipped — see `tasks/addon-trai-suffix.md`. Not required for this task.

**Tests:** `AD-ICICIT-S`, `JX-ICICIT`, `ICICIT` all normalise to `ICICIT`;
untrusted institutions are stored but never parsed; a rule learned from one
sender fires on a sibling sender of the same institution.

---

## Task 1.5 — Non-transaction pre-filter

**Hardcoded, not learned.** Runs before the rule engine. This is the main
defence against the highest-volume false positives in the corpus.

```kotlin
enum class ParseClass {
    TRANSACTION, OTP, DECLINED, SI_UPCOMING, SI_FAILED,
    AUTOPAY_SCHEDULED, PROMO, UNKNOWN
}

fun classify(body: String): ParseClass
```

> **Retrofit available:** a regulator-assigned sender suffix can classify `-P`
> and `-T` before body analysis — see `tasks/addon-trai-suffix.md`. The body
> checks below are the floor and handle the suffix-less majority regardless.

| Class | Body discriminator |
|---|---|
| OTP | `One-Time Password`, `OTP` |
| DECLINED | `declined due to`, `is declined, as` |
| SI_UPCOMING | `is due by`, `to be debited from` |
| SI_FAILED | `could not be processed` |
| AUTOPAY_SCHEDULED | `is scheduled on`, `For the upcoming mandate set for` |
| COLLECT_REQUEST | `has requested money from you` |

`successfully processed payment of` is `TRANSACTION` — a real spend. Do not
filter it.

**The discriminator appears after the amount.** Scan the whole body. An OTP
message contains a valid amount, merchant and card number and will parse as a
spend if this filter is skipped or short-circuits early.

**Tests:** each class correctly identified from real corpus examples; an OTP
message never produces a transaction; `successfully processed payment` is not
filtered.

---

## Task 1.6 — Generic extractor

Per `docs/parser.md`. Pure Kotlin, in `:core:parser` — no Android dependency, so
it is CI-testable.

```kotlin
data class ExtractedField<T>(val value: T?, val confidence: Float, val span: IntRange?)

data class GenericExtraction(
    val amount: ExtractedField<Long>,
    val direction: ExtractedField<Direction>,
    val accountLast4: ExtractedField<String>,
    val balanceAfter: ExtractedField<Long>,
    val merchant: ExtractedField<String>,
    val occurredAt: ExtractedField<Long>,
    val reference: ExtractedField<String>
)
```

**Confidence is per field, not per message.** High-confidence fields pre-fill
silently; low-confidence fields are highlighted for the user.

Output is always `source = SMS_GENERIC`, `status = PENDING_REVIEW`. No exceptions.

`span` is required — rule generation needs to know where each value sat in the
body.

**Tests:** amount with commas, decimals, `Rs.`/`INR`/`₹` prefixes; both
directions; `Avl Bal` variants; missing date falls back to `received_at`;
garbage input yields all-null with zero confidence and does not throw.

---

## Task 1.7 — Rule engine

```kotlin
interface RuleEngine {
    suspend fun parse(sms: RawSms): ParseResult
    suspend fun generateRule(sms: RawSms, confirmed: ConfirmedFields): ParserRule
    suspend fun validate(rule: ParserRule): ValidationResult
}
```

**Matching:** active rules for the sender, `priority` desc then longest pattern.
No match -> `PENDING_REVIEW`. **Never fall through to the generic extractor once
a rule exists for that sender** — a half-match produces a wrong transaction,
which is worse than no transaction.

**Generation** — from the user's corrected fields plus the `span` data:
replace variable spans with capture groups, escape everything else literally,
generalise digits and dates, anchor on surrounding literals. Prefer over-specific
to over-general.

**Validation — required before activation.** Run the candidate over every
archived `RawSms` from that sender:
1. 100ms timeout per match; reject on timeout (catastrophic backtracking)
2. Every match carrying `balance_after` must reconcile
3. Messages already parsing correctly must still parse identically
4. No conflict with an equal-priority rule

Any failure -> do not activate; return to review with the reason shown.

**Tests:** generated rule matches its source message; validation rejects a rule
that breaks an existing one; timeout rejection works; rule with
`correction_count / match_count > 0.1` is flagged.

---

## Task 1.8 — Classification

Per `docs/parser.md`. Phase 1 implements DEBIT, CREDIT, and detection-only
tagging for CARD_SPEND, CARD_PAYMENT, STATEMENT, ATM_WITHDRAWAL, REVERSAL.

**Detection only.** Tag the transaction type; do not yet build transfer linking,
card liability, or reversal netting — that is Phase 2. Tagging now means Phase 2
has data to work with rather than a backfill.

**Tests:** a card-bill-payment SMS is tagged CARD_PAYMENT, not DEBIT.

---

## Task 1.9 — Accounts and balance anchors

Per `docs/schema.md`.

- Accounts auto-suggested from sender + last4 combinations found in the archive
- `BalanceAnchor` CRUD; `source = OPENING` for the initial one
- Reconciliation computes from **the most recent anchor at or before the
  transaction**, plus confirmed transactions since — not from account creation

**Tests:** anchor resets drift; transactions before an anchor are unaffected;
reconciliation picks the correct anchor when several exist.

---

## Task 1.10 — Initialization flow

Per `docs/phases.md`.

```
1. Import full archive (Task 1.2)
2. User picks ledger_start_date — default: first of the month, 3 months back
3. Detect accounts from the archive
4. Pre-fill a BalanceAnchor per account from the earliest post-start message
   carrying a balance (source = SMS_DERIVED)
5. User confirms or overrides (override -> source = OPENING)
6. Parse forward from ledger_start_date
```

Messages before `ledger_start_date` stay as `RawSms` and are used for rule
validation. They never become transactions.

**Tests:** pre-fill picks the earliest qualifying message; manual override wins;
pre-start messages produce no transactions.

---

## Task 1.11 — Reconciliation

Per `docs/schema.md`.

```
anchor   = latest BalanceAnchor where as_of <= txn.occurred_at
baseline = anchor.balance + sum(confirmed txns between anchor.as_of and txn)
expected = baseline ± txn.amount

expected != txn.balance_after ->
    reject parse, route to PENDING_REVIEW
    flag probable missed SMS in the window
    offer: create an anchor here to reset drift
```

**Reconciliation is opportunistic, per-message — not per-account.** Most UPI
messages carry no balance. Bill payments (`InfoBIL*INFT*`), card-network
transactions (`VIN*`), ATM withdrawals (`NFS*CASH WDL*`) and cash deposits do.
Each successful reconciliation re-anchors the account.

Drift between balance-carrying messages is invisible. The UI must show
`balance_as_of` next to any balance so the user knows how stale it is.

Balance labels vary: `Avl Bal`, `Avb Bal`, `Bal`, `Available Balance`.

**Tests:** matching balance confirms and updates `balance_as_of`; mismatch
rejects and flags; a message with no balance updates the running balance without
confirming it; balance label variants all parse.

---

## Task 1.12 — Self-transfer detection

Per `docs/corpus-findings.md` §9. `PayeeAllowlist` in `docs/schema.md`.

Self-transfers appear as ordinary UPI payments whose merchant is the user's own
name. In the corpus they are frequent and large (₹5,000–₹15,000). Counting them
as expenses inflates spending severely.

- Normalise merchant: uppercase, collapse whitespace
- On allowlist match -> `Transaction.is_internal = true`, excluded from
  income/expense totals
- Allowlist entries are added **only** by explicit user confirmation from the
  review inbox. Never infer from surname — `KIRAN DHAKER` and `RAHUL DHAKAR` are
  genuine outgoing transfers, not internal movements.
- Prompt once per unseen payee that resembles a confirmed entry; do not
  auto-add.

**Tests:** `AMAN DHAKAR`, `Aman Dhakar`, `Aman  Dhakar` all match one entry;
`KIRAN DHAKER` does not match `AMAN DHAKAR`; unconfirmed names never set
`is_internal`.

---

## Task 1.13 — Review inbox

The screen that determines whether this app gets used or abandoned. Every parser
miss needs a **one-tap** fix path.

- List of `PENDING_REVIEW` transactions, newest first
- Each shows the raw SMS body with extracted spans highlighted
- Low-confidence fields visually flagged
- Edit inline; confirm generates a rule (Task 1.7) and a golden test
- Bulk actions: "ignore all from this sender", "these are all the same format"
- Empty state that reads as success, not absence

**Tests:** confirming writes a transaction, a rule, and a golden test.

---

## Task 1.14 — Golden tests

**Seed the corpus first.** `docs/corpus-findings.md` §8 lists 33 known message
skeletons across ICICI accounts, ICICI cards, SBI, Amazon Pay wallet, Zomato
Money, axio BNPL, EPFO, Razorpay and Yes Bank mandates — including every
non-transaction class. Write golden tests for each before any user confirmation
exists; they are the regression suite for the pre-filter and extractor.

Every user confirmation then writes a further `GoldenTest`: anonymised body +
expected output.

- Anonymisation: digits in account numbers -> `X`, amounts kept (needed for the
  assertion), merchant kept
- The full set runs in CI on every parser change
- Export/import as JSON so the corpus survives a reinstall

**This is the highest-leverage item in Phase 1.** Without it, fixing one bank's
parser silently breaks another's.

---

## Task 1.15 — Ledger UI

Minimal. Not the product yet.

- Account list with current balance and last-reconciled timestamp
- Transaction list per account, date-grouped
- Manual transaction entry
- Transaction detail with edit and audit history
- **"Last SMS processed: {time}"** somewhere visible — a stale value is the
  user's only signal that OEM battery optimisation is killing the receiver

---

## Task 1.16 — Missed-SMS catch-up

Per `docs/parser.md`.

- Every 6 hours, `WorkManager` scans the inbox for messages newer than the last
  processed timestamp and ingests any missing ones
- Reconciliation gaps flag a probable missed message in that window
- Surface background-execution health in the UI

**Tests:** catch-up ingests a message the receiver missed; dedupe prevents
doubles.

---

## Task 1.17 — Data export

CSV and JSON, to a user-chosen location via `ACTION_CREATE_DOCUMENT`.

Until Phase 5 there is no cloud backup, so this is the only recovery path if the
device, the app, or the signing key is lost. See `docs/signing.md`.

Include: accounts, transactions, anchors, parser rules, golden tests.

**Tests:** export round-trips through import without loss.

---

## Exit criteria

**CI**
- [ ] Generic extractor tests green
- [ ] Rule generation and validation tests green
- [ ] Reconciliation tests green
- [ ] Golden test corpus runs and passes
- [ ] detekt clean

**Device**
- [ ] Archive import completes on the real inbox
- [ ] Initialization produces correct opening balances
- [ ] Update system installs a real release

**The real exit criterion: live on it for two weeks.**
- [ ] Every SMS either parsed correctly or in review — zero silent wrong entries
- [ ] Reconciliation drift is zero, or every gap has a known cause
- [ ] Review inbox is manageable, not abandoned

If the parser cannot survive two weeks of your real messages, stop and fix it.
Phase 2 onward assumes a trustworthy ledger.
