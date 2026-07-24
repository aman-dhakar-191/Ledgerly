# Phases

One phase at a time. Do not build ahead. Each phase has an exit criterion that
must be met before moving on.

---

## Phase 0 — Foundation
Crypto, Room schema, blob serializer, migrations. No UI, no SMS.

**Exit:** all crypto tests pass; round-trip encrypt/decrypt preserves entities;
migration test passes from v1 to a dummy v2.

Detail: `tasks/phase-0.md`

---

## Initialization — how the system starts (Phase 1)

Two separate dates. Conflating them is a mistake.

| | Purpose | How far back |
|---|---|---|
| **Archive import date** | validation corpus for rule generation | as far back as the inbox goes |
| **Ledger start date** | first date transactions enter the ledger | a month boundary, 3–6 months back |

Messages between the archive date and the ledger start date are stored as
`RawSms` and used to generate and validate parser rules. They never become
transactions. Raw text is cheap; more corpus means better rules.

**Why a ledger start date is needed at all:** a ledger requires an opening
balance. Without one, every computed balance is off by an unknown constant and
reconciliation fails on every message. The start date is where a known anchor
exists — not a way to limit message volume.

### Setup sequence

```
1. Import the full SMS archive as RawSms (no parsing yet)
2. User picks ledger_start_date (default: first day of the month, 3 months back)
3. Detect accounts from senders in the archive
4. For each account, find the earliest message at/after ledger_start_date
   carrying a balance, and pre-fill a BalanceAnchor (source = SMS_DERIVED)
5. User confirms or overrides each anchor (override -> source = OPENING)
6. Parse forward from ledger_start_date, reconciling against the anchors
```

Step 4 matters: most Indian bank SMS carry `Avl Bal`, so the opening balance
usually comes from the bank rather than the user's memory. Confirmation is a
couple of taps. Manual entry remains available and is authoritative when used.

### Drift afterwards

Reconciliation failure offers a new `BalanceAnchor` at that point. Same entity,
same code path as the opening balance. Anchors reset forward drift; they do not
repair the window behind them, which must be marked lower-confidence in the UI.

---

## Phase 1 — Ledger that works
SMS receiver, sender registry, generic extractor, rule learning + validation,
review inbox, accounts, transactions, balance anchors, initialization flow,
reconciliation, golden tests, bootstrap import of existing inbox.

Also in Phase 1: the in-app update system (`tasks/update-system.md`). Needed
early because sideloaded builds are how every later phase reaches the device.

Also in Phase 1: **plain data export** (CSV or JSON, to a user-chosen location).
Small to build, and until Phase 5 lands it is the only recovery path if the app,
the device, or the signing key is lost. See `docs/signing.md`.

**Exit: live on it for two weeks.** Every real SMS either parses correctly or
appears in review. Zero silent wrong entries. If the parser can't survive this,
nothing downstream matters.

---

## Phase 2 — Correctness
Transfer detection, credit card as liability, statement SMS parsing, ATM/cash
handling, wallet and BNPL accounts, refunds and reversals, net worth.

Card-payment matching is now specified concretely — see
`docs/corpus-findings.md` §2a. The bank-side debit carries `InfoBIL*INFT*` and
pairs with a card-side `Payment of INR {amt} ... Credit Card Account {acct}` of
equal amount on the same date. Link as `Transfer(kind = CARD_PAYMENT)`; neither
side is an expense.

**Exit:** monthly income and expense totals match manual verification against
bank statements. No double-counted card bills, wallet top-ups, or
self-transfers.

---

## Phase 3 — Budget
Category taxonomy, merchant normalisation, merchant map with learning, monthly
budgets, rollover, spend-vs-budget views.

**Exit:** categorisation is >80% automatic after four weeks of corrections.

---

## Phase 4 — Investments
Instrument/Holding/BalanceSnapshot, PPF and EPF entry, ScheduleRule,
Contribution matching, price feed for market instruments.

**EPF balances arrive by SMS** (`EPFOHO` senders) with both the passbook balance
and the monthly contribution — see `docs/corpus-findings.md` §12. The earlier
assumption that EPF required manual entry from the EPFO portal was wrong. PPF
still needs manual balance entry, though PPF contribution debits from ICICI will
parse normally.

Demat and broker senders (`CDSLTX`, `NSESMS`, `BSELTD`, `KFINCR`, `UPSTX`) also
appear in the corpus and belong to this phase.

**Exit:** SIP debits auto-confirm against expected contributions; portfolio value
reconciles against broker/EPFO statements.

---

## Phase 5 — Sync
Firestore blob backup, biometric unlock in production use, conflict merge,
multi-device restore test.

**Exit:** wipe app data, reinstall, restore from passphrase, verify full ledger
integrity.

---

## Phase 6 — Intelligence
LLM parser fallback -> categorisation tiers -> anomaly detection (rolling
mean/stddev, >2σ) -> forecasting -> insight narration.

Rules before models, always. Every model output is a suggestion.

**Exit:** each tier measurably beats the rules-only baseline. If it doesn't,
delete it.

---

## Phase 7 — Liabilities and planning
Loans with amortisation, card EMI handling, sinking funds for non-monthly
expenses, tax section summaries, goals, emergency fund tracking.

---

## Deferred / maybe never

**Notification listener (`NotificationListenerService`)** — reads UPI app
notifications (GPay, PhonePe, Paytm).

**Not needed.** The corpus analysis found SMS coverage for every source
originally thought to be a gap, including axio Pay Later spends
(`docs/corpus-findings.md` §10). Nothing in the current picture requires it.

Were it ever reconsidered, the objections remain:
- Notification text is UI copy: unstable across app updates, no balance field,
  no reliable reference ID
- **Double-counts by default.** A GPay payment from a bank account produces both
  a GPay notification and a bank SMS, with no shared identifier to dedupe on
- Ephemeral — no archive, so a parser fix cannot be backfilled, which breaks the
  rule-validation model the whole parser design rests on

- Shared and split expenses
- Multi-currency FX rates
- Bank statement PDF import
- Account Aggregator (RBI AA) integration
- Widgets, Wear OS

---

## Reality check

Phases 0–2 are roughly 70% of the real work and the least visually rewarding.
Expect that to feel slow. The temptation to jump to budgets and charts before the
ledger is trustworthy is the main way this project fails.
