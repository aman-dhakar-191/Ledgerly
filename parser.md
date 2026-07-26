# Parser

No bank formats are hardcoded. Rules are **data**, stored in Room, learned at
runtime from the user's own messages.

**Read `docs/corpus-findings.md` alongside this file.** It records observations
from the real inbox and overrides general assumptions here.

---

## Sender normalisation — before anything else

Sender IDs identify the telecom route, not the bank. `AD-ICICIT-S`,
`JX-ICICIT-S` and `VM-ICICIT-S` are all ICICI with identical message formats.

The structure is regulated (TRAI TCCCPR): `XY-HEADER-Z` where `XY` is
operator+circle, `HEADER` is the entity, and `Z` is a message-type suffix
mandated since 6 May 2025.

```
normalizeSender(raw) = strip leading /^[A-Z]{2}-/ and trailing /-[A-Z]$/
```

Rules key on the resulting **institution** (`ICICIT`), never the raw sender.
`RawSms` keeps the raw value for provenance.

### The suffix is a regulated classification signal — read it before the body

The trailing letter is assigned by the telco from the registered template, not
guessed from text. Treat it as an authoritative first-pass classifier:

| Suffix | Meaning | Parser use |
|---|---|---|
| `-P` | Promotional | **Never a transaction.** Drop before body analysis. |
| `-T` | Transactional — since 2020, **OTP only** for banks | Strong OTP signal. `AD-ICICIO-T` in the corpus is the OTP sender. |
| `-S` | Service | Where debit/credit/balance messages live. Real transactions. |
| `-G` | Government | EPFO, tax. EPF balance arrives here. |

`-P` and `-T` essentially never carry a ledger transaction — a more robust cut
than keyword matching, because the telco assigns it from the registered
template. Fall through to body analysis for `-S`, `-G`, and suffix-less messages
(older ones predating May 2025 — the corpus contains these).

**Do not rely on the suffix alone.** It classifies message *type*, not
bank-vs-card-vs-wallet, and pre-2025 messages lack it. It narrows the work; the
body and learned rules still do the parsing. Store the parsed suffix on `RawSms`
for use by the pre-filter and for diagnostics.

---

## Non-transaction pre-filter — hardcoded, runs before rule matching

**Step 0 — suffix check.** If the sender suffix is `-P` (promotional) or `-T`
(transactional/OTP-only for banks), classify immediately (PROMO / OTP) and stop.
This is regulator-assigned and needs no body analysis. Only `-S`, `-G` and
suffix-less messages proceed to the checks below.

These classes contain a plausible amount, merchant and account but represent no
money movement. A learned rule would happily parse them as transactions.

| Class | Discriminator | Outcome |
|---|---|---|
| OTP | `One-Time Password`, `OTP` | never a transaction |
| DECLINED | `declined due to`, `is declined, as` | never a transaction |
| SI_UPCOMING | `is due by`, `to be debited from` | future intent -> Phase 4 |
| SI_FAILED | `could not be processed` | never a transaction |
| AUTOPAY_SCHEDULED | `is scheduled on`, `For the upcoming mandate set for` | future intent -> Phase 4 |
| COLLECT_REQUEST | `has requested money from you` | a request, not a debit |
| PROMO | no amount, or marketing sender class | ignored |

`SI_PROCESSED` (`successfully processed payment of`) **is** a real transaction —
do not filter it.

The discriminator usually appears *after* the amount, so the filter must examine
the whole body. Stopping at the first amount match misclassifies all of these.

This filter is hardcoded and not user-editable. It is the main defence against
the highest-volume false positives in the corpus.

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
  +- normalizeSender -> institution
  |
  +- non-transaction pre-filter (OTP, declined, SI upcoming/failed, autopay)
  |     match -> store RawSms with that class, stop. Never a transaction.
  |
  +- institution in SenderRegistry?
  |     no  -> prompt: "New institution {id}. Bank / Card / OTP / Spam?"
  |            untrusted -> store RawSms, mark IGNORED, stop
  |
  +- active ParserRule for institution?
  |     yes -> match by priority desc
  |             match    -> reconcile (if balance present) -> CONFIRMED
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
| amount | `Rs.` / `Rs` / `Rs{n}` (**no space**, axio) / `INR` / `USD` / `₹` + number with optional commas. **0, 1 or 2 decimal places** (`by 2`, `by 50.0`, `by 210.25`), bare leading decimal (`Rs .00`), and trailing `/-` (EPFO). |
| balance_after | `Avl Bal`, `Avb Bal`, `Bal`, `Available Balance`, `Avbl Bal`, `Updated Balance is`, `Updated balance:` + amount. **`Avl Limit` is NOT a balance** — it is remaining credit on a card; `outstanding = limit - available`. |
| currency | `INR`, `Rs`, `₹` -> INR; `USD` -> USD. Required — the corpus contains USD. |
| direction | debited, withdrawn, spent, paid, sent -> DEBIT; credited, received, deposited, refund -> CREDIT |
| account last4 | `A/c`, `Acct`, `Card`, `Account` + masked digits in any of these forms: `XX9001`, `XX924`, `4xxx5001`, `XXXXX583840`, `XXXXXXXX3840`, or bare `6001`. **Extract the trailing digit run and match on it.** |
| merchant | after `at`, `to`, `towards`, `for UPI-{ref}-`, `Merchant`, `trf to`, or before `credited` |
| occurredAt | `dd-MMM-yy`, `dd-MMM-YY`, `dd/MM/yyyy`, `dd-MM-yy`; absent -> `received_at` |
| reference | `UPI-`, `UPI:`, `Ref`, `RRN`, `UTR`, `Txn ID`, `IMPS Ref no`, `Mandate ID` |

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
5. **DLT sanity bounds.** Registered templates permit at most **5 variables**,
   each at most **30 characters** (TRAI TCCCPR). A generated rule with more than
   5 capture groups, or a group that routinely matches >30 characters, is
   malformed — reject and regenerate. This bounds greedy `(.+?)` groups that
   would otherwise swallow trailing text.

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
- **Target the DLT template shape.** Bank SMS are authored from registered
  templates where variable slots are marked `{#var#}` and fixed text is
  preserved. Ask the model to convert the SMS into that shape — a template with
  `{#var#}` for the variable parts — rather than free-form regex. It is a more
  constrained, more natural generation target, and it maps directly onto the
  rule pattern. Apply the same 5-variable / 30-char bounds as rule validation.

---

## Golden tests

Every user confirmation writes a `GoldenTest` row: anonymised body + expected
output. The full set runs in CI on every parser change.

This is the highest-leverage thing in Phase 1. Without it, fixing one bank's
parser silently breaks another's, repeatedly.
