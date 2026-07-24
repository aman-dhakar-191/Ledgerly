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

## Phase 1 — Ledger that works
SMS receiver, sender registry, generic extractor, rule learning + validation,
review inbox, accounts, transactions, reconciliation, golden tests, bootstrap
import of existing inbox.

**Exit: live on it for two weeks.** Every real SMS either parses correctly or
appears in review. Zero silent wrong entries. If the parser can't survive this,
nothing downstream matters.

---

## Phase 2 — Correctness
Transfer detection, credit card as liability, statement SMS parsing, ATM/cash
handling, refunds and reversals, net worth.

**Exit:** monthly income and expense totals match manual verification against
bank statements. No double-counted card bills.

---

## Phase 3 — Budget
Category taxonomy, merchant normalisation, merchant map with learning, monthly
budgets, rollover, spend-vs-budget views.

**Exit:** categorisation is >80% automatic after four weeks of corrections.

---

## Phase 4 — Investments
Instrument/Holding/BalanceSnapshot, PPF and EPF manual entry, ScheduleRule,
Contribution matching, price feed for market instruments.

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
