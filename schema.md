# Schema

All money fields are `Long` paise. All timestamps are `Long` epoch millis, UTC.
All entities carry `id: String` (UUID v4), `created_at`, `updated_at`,
`deleted_at: Long?`.

Phase tags indicate when an entity is first built. Do not build ahead.

---

## Ingest layer

### RawSms — Phase 1
Immutable. Never edited, never deleted.

| Field | Type | Notes |
|---|---|---|
| id | String | UUID |
| sender | String | e.g. `VM-HDFCBK` |
| body | String | verbatim, unmodified |
| received_at | Long | epoch millis |
| subscription_id | Int? | dual SIM |
| dedupe_hash | String | SHA-256(sender + received_at + body), unique index |
| institution | String | normalised sender |
| sender_suffix | String? | TRAI message-type suffix: P, S, T, G, or null (pre-May-2025). Regulator-assigned; used as the first pre-filter cut. See `docs/parser.md`. |
| parse_status | Enum | UNPROCESSED, PARSED, REVIEW, IGNORED, FAILED |
| parse_class | Enum | TRANSACTION, OTP, DECLINED, SI_UPCOMING, SI_FAILED, AUTOPAY_SCHEDULED, PROMO, UNKNOWN |
| matched_rule_id | String? | |

`parse_class` is set by the hardcoded pre-filter in `docs/parser.md`. Anything
other than `TRANSACTION` never reaches the rule engine. OTP and declined
messages carry amounts and merchants and would otherwise parse as real spends.

### SenderRegistry — Phase 1

| Field | Type | Notes |
|---|---|---|
| sender_id | String | PK, raw e.g. `AD-ICICIT-S` |
| institution | String | normalised e.g. `ICICIT` — **rules key on this** |
| label | String | user-assigned |
| type | Enum | BANK, CARD, OTP, PROMO, SPAM, UNKNOWN |
| trusted | Boolean | only trusted senders are parsed |
| account_id | String? | default account for this sender |

Sender IDs identify the telecom route, not the bank: `AD-ICICIT-S`,
`JX-ICICIT-S` and `VM-ICICIT-S` all carry identical ICICI formats. Trust and
classification apply at the institution level. See `docs/corpus-findings.md`.

### ParserRule — Phase 1

| Field | Type | Notes |
|---|---|---|
| id | String | |
| institution | String | **not sender_id** — a rule learned from one route must fire on all |
| pattern | String | regex |
| field_map | String | JSON: capture group -> field name |
| txn_type | Enum | DEBIT, CREDIT, CARD_SPEND, CARD_PAYMENT, STATEMENT, TRANSFER, SI_PROCESSED, REFUND, EMI_CONVERSION |
| priority | Int | higher wins on conflict |
| confidence | Float | 0.0–1.0 |
| active | Boolean | |
| created_from_sms_id | String | provenance |
| match_count | Int | |
| correction_count | Int | health signal; high = regenerate |
| version | Int | |

### GoldenTest — Phase 1

| Field | Type | Notes |
|---|---|---|
| id | String | |
| raw_body | String | anonymised |
| expected_json | String | serialized expected ParsedTransaction |
| rule_id | String? | |

---

## Ledger layer

### Account — Phase 1

| Field | Type | Notes |
|---|---|---|
| id | String | |
| name | String | |
| type | Enum | SAVINGS, CURRENT, CREDIT_CARD, CASH, WALLET, LOAN, BNPL |

Types observed in the corpus beyond bank accounts and cards:
- `WALLET` — Amazon Pay, Zomato Money. Funded by a bank debit; the top-up is a
  transfer and the wallet debit is the real expense. Ignoring the wallet
  double-counts. Some wallet formats carry a balance and are reconcilable.
- `BNPL` — axio (Amazon Pay Later). Structurally a credit card: individual spends
  SMS, a monthly bill, and a settlement debit from the bank to `CAPITALFLOAT`.
  Spends carry no merchant name, so they arrive uncategorised.
- `CASH` — destination for ATM withdrawals. Spending from it is invisible; this
  is the one genuine blind spot.
| last4 | String? | |
| currency | String | ISO 4217, default INR |
| current_balance | Long | paise; negative for liabilities |
| balance_as_of | Long | timestamp of last **verified** balance, i.e. last reconciled message |

Reconciliation is **opportunistic, per-message**. Most UPI messages carry no
balance; bill payments, card-network transactions, ATM withdrawals and cash
deposits do. Each balance-carrying message re-anchors the account. Drift between
them is invisible until the next one arrives, so the UI must show
`balance_as_of` alongside any balance. See `docs/corpus-findings.md` §2.
| credit_limit | Long? | CREDIT_CARD only |
| statement_day | Int? | CREDIT_CARD only, 1–31 |
| due_day | Int? | CREDIT_CARD only |
| archived | Boolean | |

### Transaction — Phase 1

| Field | Type | Notes |
|---|---|---|
| id | String | |
| account_id | String | FK |
| amount | Long | always positive; direction carries sign |
| currency | String | ISO 4217. Required — the corpus contains USD card transactions. Non-INR excluded from INR totals; no FX conversion in Phase 1. |
| direction | Enum | DEBIT, CREDIT |
| occurred_at | Long | |
| merchant_raw | String? | as it appeared |
| merchant_normalized | String? | Phase 3 |
| category_id | String? | Phase 3 |
| balance_after | Long? | reported by SMS, used for reconciliation |
| raw_sms_id | String? | FK, null for manual entries |
| source | Enum | SMS_RULE, SMS_GENERIC, MANUAL, IMPORT |
| status | Enum | CONFIRMED, PENDING_REVIEW, REJECTED |
| transfer_id | String? | set if part of a Transfer |
| is_internal | Boolean | excluded from income/expense totals |
| notes | String? | |

Indexes: `(account_id, occurred_at)`, `(status)`, `(transfer_id)`.

### TransactionAudit — Phase 1

| Field | Type | Notes |
|---|---|---|
| id | String | |
| transaction_id | String | |
| field | String | |
| old_value | String? | |
| new_value | String? | |
| changed_at | Long | |
| reason | Enum | USER_EDIT, RULE_BACKFILL, RECONCILE_FIX |

### BalanceAnchor — Phase 1

A user-asserted true balance for an account at a point in time. Opening balances
and later drift corrections are the same operation and use the same entity.

| Field | Type | Notes |
|---|---|---|
| id | String | |
| account_id | String | FK |
| balance | Long | paise, asserted truth |
| as_of | Long | |
| source | Enum | OPENING, USER_CORRECTION, SMS_DERIVED |
| note | String? | why the correction was made |

Index: `(account_id, as_of)`.

Reconciliation runs from **the most recent anchor at or before the
transaction**, not from account creation. A new anchor resets accumulated drift
to zero and prevents errors compounding forward.

An anchor does **not** repair transactions before it. If drift arose from three
missed messages in May, a June anchor makes future balances correct but May's
totals stay understated. Therefore:

- every anchor writes a `TransactionAudit` row
- the UI must mark the window between the previous anchor and this one as
  "reconciled from anchor", so aggregate totals in that window are visibly
  lower-confidence

### PayeeAllowlist — Phase 1

Payee names that identify the user's own accounts. Self-transfers appear as
ordinary UPI payments with the user's own name as merchant, and are frequent and
large — counting them as expenses inflates spending severely.

| Field | Type | Notes |
|---|---|---|
| id | String | |
| normalized_name | String | uppercase, whitespace collapsed |
| account_id | String? | the own-account this payee maps to, if known |
| confirmed_at | Long | user confirmation is required |

Matching: uppercase and collapse whitespace, then exact match.
`AMAN DHAKAR`, `Aman Dhakar`, `Aman  Dhakar` all normalise to `AMAN DHAKAR`.

**Never infer from surname.** `KIRAN DHAKER` and `RAHUL DHAKAR` also appear in
the corpus and are genuine outgoing transfers to family. Entries are added only
by explicit user confirmation from the review inbox.

On match, set `Transaction.is_internal = true`. If the counterpart transaction
exists (equal amount, same date, a different own-account), link as
`Transfer(kind = ACCOUNT_TO_ACCOUNT)` in Phase 2.

### Transfer — Phase 2

| Field | Type | Notes |
|---|---|---|
| id | String | |
| from_txn_id | String | |
| to_txn_id | String? | null if counterpart never seen |
| kind | Enum | ACCOUNT_TO_ACCOUNT, CARD_PAYMENT, INVESTMENT_FUNDING, ATM_WITHDRAWAL |
| detected_by | Enum | AUTO, MANUAL |
| confidence | Float | |

Detection: opposite directions, amount equal, both accounts owned by user,
occurred within 72h. Never crosses into income/expense aggregates.

### Category — Phase 3

| Field | Type | Notes |
|---|---|---|
| id | String | |
| name | String | |
| parent_id | String? | two levels max |
| icon | String? | |
| is_income | Boolean | |
| tax_section | String? | e.g. `80C` |

### MerchantMap — Phase 3

| Field | Type | Notes |
|---|---|---|
| id | String | |
| normalized_key | String | unique |
| category_id | String | |
| weight | Int | increments on user confirmation |
| source | Enum | USER, LEARNED, SEED |

---

## Planning layer

### Budget — Phase 3

| Field | Type | Notes |
|---|---|---|
| id | String | |
| category_id | String | |
| period | Enum | MONTHLY, WEEKLY, YEARLY |
| amount | Long | |
| rollover | Boolean | unspent carries forward |
| active_from | Long | |
| active_to | Long? | |

### ScheduleRule — Phase 4

Generates expected `Contribution` / recurring expense rows ahead of time.

| Field | Type | Notes |
|---|---|---|
| id | String | |
| target_type | Enum | INSTRUMENT, EXPENSE, LOAN |
| target_id | String | |
| frequency | Enum | MONTHLY, QUARTERLY, YEARLY |
| day_of_month | Int | |
| amount | Long | |
| payee_token | String? | narration fragment used for matching |
| active_from | Long | |
| active_to | Long? | |

### Contribution — Phase 4

| Field | Type | Notes |
|---|---|---|
| id | String | |
| instrument_id | String | |
| expected_amount | Long | |
| expected_date | Long | |
| status | Enum | PENDING, CONFIRMED, MISSED, MANUAL |
| matched_txn_id | String? | |

Matching: amount within 1%, date within ±3 days, narration contains
`payee_token`. Two of three required. No match by due_date+5d -> MISSED.

### SinkingFund — Phase 7
Amortises non-monthly expenses (insurance, annual fees) across months.

### Goal — Phase 7

---

## Assets layer

### Instrument — Phase 4

| Field | Type | Notes |
|---|---|---|
| id | String | |
| name | String | |
| type | Enum | MARKET, STATEMENT, CASHFLOW |
| account_ref | String? | |
| currency | String | |
| tax_section | String? | |

- `MARKET` — value = units x latest price (stocks, MF, ETF)
- `STATEMENT` — value = latest BalanceSnapshot (PPF, EPF)
- `CASHFLOW` — value = sum of confirmed Contributions (RD, endowment)

### Holding — Phase 4
`MARKET` only.

| Field | Type | Notes |
|---|---|---|
| id | String | |
| instrument_id | String | |
| units | Double | not money; Double acceptable |
| avg_cost | Long | paise per unit |

### BalanceSnapshot — Phase 4
`STATEMENT` only.

| Field | Type | Notes |
|---|---|---|
| id | String | |
| instrument_id | String | |
| balance | Long | |
| as_of_date | Long | |
| source | Enum | MANUAL, STATEMENT_IMPORT, INTERPOLATED |

`INTERPOLATED` values must be visually distinct in the UI. Never present an
estimate as a known balance.

### PriceSnapshot — Phase 4

---

## Liabilities layer — Phase 7

### Loan
`principal`, `rate`, `tenure_months`, `emi_amount`, `start_date`, `account_id`

### AmortSchedule
Per-instalment principal/interest split. EMI debit posts as: principal ->
transfer (debt reduction), interest -> expense. Never the full EMI as expense.

### CardEmi
Purchase converted to EMI. Original spend counts once; monthly instalments post
as principal transfer + interest expense, never as new spend.

---

## Insight layer — Phase 6

Derived, not stored. Computed views over the above.

- **NetWorth** = Σ asset accounts + Σ instrument values − Σ liability balances
- **CashFlow** = Σ CREDIT − Σ DEBIT, excluding `is_internal`
- **Runway** = liquid balance ÷ trailing 3-month average outflow
- **TaxSummary** = Σ contributions grouped by `tax_section`

---

## Reconciliation

Runs on every SMS-derived transaction carrying `balance_after`.

The baseline is the most recent `BalanceAnchor` at or before the transaction,
plus every confirmed transaction since that anchor. Not `account.current_balance`
from creation — anchors are what stop drift compounding.

```
anchor   = latest BalanceAnchor where as_of <= txn.occurred_at
baseline = anchor.balance + sum(confirmed txns between anchor.as_of and txn)
expected = baseline ± txn.amount

if expected != txn.balance_after:
    reject parse -> PENDING_REVIEW
    flag probable missed SMS between the last reconciled point and this txn
    offer the user: create a BalanceAnchor here to reset drift
else:
    account.current_balance = txn.balance_after
    account.balance_as_of   = txn.occurred_at
```

Transactions with no `balance_after` cannot be reconciled; they update the
running balance but do not confirm it.

This is the primary automated correctness check in the system. It catches
misparsed amounts, missed messages, and bad learned rules without user attention.
