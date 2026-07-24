# Parser

No bank formats are hardcoded. Rules are **data**, stored in Room, learned at
runtime from the user's own messages.

---

## The ladder

```
Tier 1  ParserRule (learned, sender-specific)   -> may write autonomously
Tier 2  Generic extractor                        -> suggestion only
Tier 3  Review inbox (user)                      -> source of truth
```

**Tier 2 is a seed, never a fallback.**

Once any active rule exists for a sender, the generic extractor is permanently
disabled for that sender. If the specific rule fails on a message, that message
goes to review — it is *not* re-parsed by the generic tier.

Rationale: a generic parser that half-matches produces a wrong transaction, not
no transaction. Wrong amounts entering the ledger silently is worse than a
message waiting in review. Failure must be loud.

---

## Flow

```
SMS arrives
  |
  +- sender in SenderRegistry?
  |     no  -> prompt: "New sender {id}. Bank / Card / OTP / Spam?"
  |            untrusted -> store RawSms, mark IGNORED, stop
  |
  +- active ParserRule for sender?
  |     yes -> match by priority desc
  |             match    -> reconcile -> CONFIRMED
  |             no match -> PENDING_REVIEW (do NOT fall to generic)
  |
  +- no rules yet
        -> generic extractor -> pre-filled suggestion -> PENDING_REVIEW
        -> user confirms/corrects
        -> generate rule -> validate -> activate
        -> store as GoldenTest
```

---

## Generic extractor (Tier 2)

Field-level confidence, not message-level. High-confidence fields are pre-filled
silently; low-confidence fields are highlighted for user attention.

| Field | Signals |
|---|---|
| amount | `Rs.` / `INR` / `₹` followed by number with optional commas/decimals |
| direction | debited, withdrawn, spent, paid, sent -> DEBIT; credited, received, deposited, refund -> CREDIT |
| account last4 | `A/c`, `Ac`, `Account`, `Card` + `XX1234` / `xx1234` / `*1234` |
| balance_after | `Avl Bal`, `Available Balance`, `Bal:`, `Avbl Bal` + amount |
| merchant | text after `at`, `to`, `towards`, `VPA`, trailing before `on {date}` |
| occurred_at | `dd-MM-yy`, `dd/MM/yyyy`, `dd-MMM-yy`; absent -> SMS received_at |
| ref | `Ref`, `RRN`, `UPI Ref`, `Txn ID` |

Output is always `source = SMS_GENERIC`, `status = PENDING_REVIEW`. No exceptions.

---

## Classification

Runs before ledger write. Getting this wrong double-counts.

| Type | Signals | Ledger effect |
|---|---|---|
| CARD_SPEND | card last4 present, "spent on card", merchant present | expense against card account |
| CARD_PAYMENT | from bank account, narration has `CC PAYMENT` / `CREDIT CARD` / `AUTOPAY` / `BILLDESK` | **transfer** bank -> card, never an expense |
| STATEMENT | `total due`, `min due`, `due date`, no debit verb | not a transaction; updates card outstanding, creates PENDING contribution |
| TRANSFER | both accounts owned, opposite direction, equal amount, within 72h | `is_internal = true` |
| ATM_WITHDRAWAL | `ATM`, `cash wdl`, `withdrawn at` | transfer to CASH account |
| DEBIT / CREDIT | default | normal expense/income |
| REVERSAL | `reversed`, `refund`, `failed`, matches a prior debit | nets against original; not income |

**Never both.** A card bill payment is either an expense or a transfer, and it is
always a transfer.

---

## Rule generation

From a user-corrected extraction:

1. Take the raw body and the confirmed field values
2. Locate each value's position in the body
3. Replace variable spans with capture groups; escape everything else literally
4. Generalise: digits -> `[\d,]+\.?\d*`, dates -> date alternation,
   merchant -> `(.+?)` bounded by its literal neighbours
5. Anchor on stable literals surrounding each group
6. Store pattern + `field_map` JSON

Prefer over-specific to over-general. A rule that matches too narrowly costs one
extra review; a rule that matches too broadly corrupts the ledger.

---

## Rule validation — required before activation

Run the candidate over **every archived RawSms from that sender**:

1. **No crash / no catastrophic backtracking.** Enforce a 100ms timeout per
   match; reject on timeout.
2. **Balance reconciliation.** Every match carrying `balance_after` must
   reconcile against the preceding known balance.
3. **No regression.** Messages already parsing correctly under existing rules
   must still parse identically.
4. **No conflict.** If it matches messages belonging to another rule with equal
   priority, reject and ask the user to disambiguate.

Any failure -> do not activate, return to review with the reason shown.

This is why the immutable `RawSms` archive matters more in a learned design than
a hardcoded one. It is the validation corpus.

---

## Rule health

- `match_count` increments on every successful parse
- `correction_count` increments when the user edits a transaction that a rule
  produced
- `correction_count / match_count > 0.1` -> flag the rule for regeneration.
  Regenerate rather than patch; a rule you keep fixing is the wrong rule.
- Conflicts resolve by `priority` desc, then longest pattern (most specific wins)

---

## Backfill

When a rule is added or changed, re-run over `RawSms` where
`parse_status IN (REVIEW, FAILED)` for that sender. Newly parsed transactions
write with `source = SMS_RULE` and an audit row with `reason = RULE_BACKFILL`.

Never overwrite a user-confirmed transaction during backfill.

---

## Missed-SMS detection

Android will drop messages — battery optimisation, doze, OEM process killing.

- Every 6 hours, `WorkManager` scans the SMS inbox for messages newer than the
  last processed timestamp and ingests any that are missing (dedupe hash makes
  this safe)
- Surface "last SMS processed: {time}" in the UI. A stale value is the user's
  signal that background execution is being killed.
- Reconciliation gaps (`balance_after` mismatch with no explaining transaction)
  flag a probable missed message in that window

---

## LLM tier — Phase 6 only

Not built before Phase 6. When built:

- Invoked **only** for a sender with no rules, and only on explicit user tap
- Strips account numbers and names before the request
- Output is a suggestion in review, identical status to Tier 2 — never a direct
  write
- Its purpose is to *generate a rule*, not to parse ongoing messages. One call
  per new format, not one per message.

---

## Golden tests

Every user confirmation writes a `GoldenTest` row: anonymised body + expected
output. The full set runs in CI on every parser change.

This is the highest-leverage thing in Phase 1. Without it, fixing one bank's
parser silently breaks another's, repeatedly.
