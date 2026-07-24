# Phase 2 — Correctness

Read `CONTEXT.md`, `docs/schema.md`, and `docs/corpus-findings.md` before
starting. §2a, §9, §10 and §11 specify most of this phase concretely.

**Goal:** the numbers become trustworthy. After Phase 1 the ledger records
transactions; after Phase 2 the totals mean something. Every double-count path
identified in the corpus is closed.

**In scope:** transfer detection and linking, credit card as liability, statement
parsing, wallet accounts, BNPL accounts, ATM/cash, refunds and reversals, net
worth.

**Out of scope:** categories, budgets, merchant learning (Phase 3); investments
(Phase 4); sync (Phase 5); anything LLM (Phase 6); loans and EMI amortisation
(Phase 7).

---

## Phase 1 gate

Before starting, confirm the Phase 1 exit criterion actually held:

- [ ] Two weeks of daily use on real messages
- [ ] Zero silent wrong entries
- [ ] Reconciliation drift zero, or every gap explained
- [ ] Review inbox manageable

Phase 2 layers correctness logic on top of the ledger. If the ledger is wrong
underneath, this work compounds the error. Phase 2 tasks may proceed in parallel
with the soak period, but Phase 3 must not start until this gate passes.

---

## Task 2.1 — Transfer entity and linking

Per `docs/schema.md`.

```kotlin
interface TransferDetector {
    suspend fun findCounterpart(txn: Transaction): Transaction?
    suspend fun link(from: Transaction, to: Transaction, kind: TransferKind): Transfer
    suspend fun unlink(transferId: String)
}
```

General matching criteria:
- opposite directions
- equal amount (exact — these are the same movement, not an approximation)
- both accounts owned by the user
- within 72 hours

On link: both transactions get `transfer_id` and `is_internal = true`, excluding
them from income and expense aggregates.

**One-sided transfers must still be marked.** Where only one leg produces an SMS
(common with card payments and wallet top-ups), set `is_internal` on the visible
leg without creating a `Transfer` row. An unmatched internal movement is still
not an expense.

Manual link and unlink from the transaction detail screen — auto-detection will
miss cases, and a wrong link is worse than none.

**Tests:** exact amount match links; ±1 paise does not; 73-hour gap does not;
same-direction pair does not; unlink restores both transactions to normal.

---

## Task 2.2 — Card payment matching

Per `docs/corpus-findings.md` §2a. **The single highest-value task in this
phase** — unmatched card payments inflate expenses by the full bill amount every
month.

```
Bank side:  ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26
            InfoBIL*INFT*FGR6.Avl Bal Rs. 8,611.98.

Card side:  Dear Customer, Payment of INR 2,170.00 has been received on your
            ICICI Bank Credit Card Account 4xxx5001 on 23-JUL-26.Thank you.
```

Matching:
1. bank debit narration contains `InfoBIL*INFT*`
2. card-side credit of equal amount, same date
3. link as `Transfer(kind = CARD_PAYMENT)`

`InfoBIL*INFT*` also covers non-card bill payments, so the card-side match is
what confirms it. A bank debit with `InfoBIL*INFT*` and no card counterpart is a
normal bill payment, not a transfer.

Observed same-day timestamp gaps range from seconds to ~35 minutes — match on
date, not clock time.

**Tests:** matched pair produces one transfer and zero expense; `InfoBIL*INFT*`
debit with no counterpart stays a normal expense; two same-amount payments on one
day to different cards match correctly (this occurs in the corpus — 23-Jul-26 has
four).

---

## Task 2.3 — Credit card as liability

```
CreditCardAccount : Account
  credit_limit, statement_day, due_day, current_outstanding
```

- Card spends increase outstanding
- Card payments decrease outstanding
- Refunds decrease outstanding
- Outstanding contributes to net worth as **negative**

**Two balance sources, neither a bank balance:**
- `Avl Limit: INR 15,468.00` on the spend format → `outstanding = credit_limit − available_limit`. Requires `credit_limit` to be known; prompt for it once per card.
- `Total of Rs 10,391.94` on the statement → authoritative outstanding at statement date.

Do not feed either into bank-account reconciliation.

**Tests:** spend increases outstanding; payment decreases it; `Avl Limit`
back-computes outstanding correctly given a known limit; net worth treats
outstanding as a liability.

---

## Task 2.4 — Statement parsing

```
ICICI Bank Credit Card XX6001 Statement is sent to {email}.
Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26.

Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00
by 23-Jul-26 towards ICICI Bank Credit Card XX5001.
```

Not transactions. Each sets `current_outstanding`, `minimum_due` and `due_date`
on the card, and creates a `PENDING` payment obligation.

**Reconciliation check:** when a statement arrives, compare
`sum(card transactions since last statement)` against the statement total.
Mismatch means missed messages, interest, or fees — create a balancing
adjustment entry flagged for review rather than silently accepting either number.
This is the card equivalent of bank balance reconciliation and the only accuracy
check available for cards.

**Tests:** both statement formats parse; neither creates a transaction; mismatch
produces a flagged adjustment.

---

## Task 2.5 — Wallet accounts

Per `docs/corpus-findings.md` §10. Amazon Pay and Zomato Money.

The funding leg and the spend leg are both visible, so ignoring wallets
double-counts:

```
Funding:  ICICI Bank Acct XX924 debited for Rs 500.00 on 02-Jun-25;
          Amazon Pay Bala credited. UPI:...
Spend:    Your Apay Wallet balance is debited for INR 140.00. Reference Number is ...
```

- Funding → `Transfer(kind = ACCOUNT_TO_ACCOUNT)` bank → wallet
- Wallet debit → the real expense
- Formats carrying `Updated Balance is` / `Updated balance:` reconcile normally

Funding merchant strings observed: `Amazon Pay`, `Amazon Pay Bala`,
`Amazon Pay Balan`, `Amazon Bill Pay`. Normalise before matching.

**Tests:** funding creates a transfer not an expense; wallet debit is an expense;
balance-carrying wallet formats reconcile; a wallet spend with no prior funding
still records (partial history is normal).

---

## Task 2.6 — BNPL account (axio)

Per `docs/corpus-findings.md` §10. Structurally a credit card.

- `BNPL_SPEND` / `BNPL_SPEND_EMI` → expense against the axio account, increasing
  its outstanding
- `BNPL_BILL_DUE` → sets the expected settlement amount and date (5th of month)
- ICICI debit to `CAPITALFLOAT` → `Transfer(kind = CARD_PAYMENT)` settling it
- `BNPL_LIMIT_CHANGE` → updates `credit_limit`

**Spends carry no merchant name.** They arrive uncategorised; Phase 3 handles
that. Do not attempt merchant inference here.

Reconciliation: `sum(spends since last bill)` should equal the bill amount.
Mismatch flags for review, same as card statements.

Verify whether both observed account numbers (`XXX0012`, `XXX7676`) are live.

**Tests:** both spend formats parse; settlement debit links as a transfer;
spend total reconciles against bill amount.

---

## Task 2.7 — ATM and cash

```
ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*.
Avb Bal Rs. 32,327.01.
```

Withdrawal → `Transfer(kind = ATM_WITHDRAWAL)` to a `CASH` account, not an
expense. The money still exists; it changed form.

**Cash spending is genuinely invisible** — the one remaining blind spot. Options,
in order of how much they'll actually be used:
1. Leave the balance as "Cash — unallocated" and accept it
2. Prompt for a rough split at withdrawal time
3. Manual cash entry

[Build option 1. Offer 2 as a setting. Do not force any of them — an unused
prompt is worse than an honest unknown.]

**Tests:** withdrawal creates a transfer and does not appear in expense totals;
cash balance increases.

---

## Task 2.8 — Refunds and reversals

```
AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on
13-JAN-26. Revised total due Rs 5,377.55, minimum due Rs .00
```

A refund is **not income**. It nets against the original spend.

- Match by merchant + amount within 90 days, most recent first
- On match: link to the original, reduce that spend's effective amount
- No match: record as a standalone credit flagged for review, never as income

Also handle failed-payment reversals: a debit followed by an equal credit from
the same merchant within days.

**Tests:** refund nets against the matching spend; unmatched refund is flagged,
not counted as income; partial refund reduces the original correctly.

---

## Task 2.9 — Net worth

```
NetWorth = Σ asset accounts
         + Σ cash and wallet balances
         − Σ card outstanding
         − Σ BNPL outstanding
```

Derived, not stored. Investments arrive in Phase 4; loans in Phase 7.

**Every balance must carry its `balance_as_of`.** Reconciliation is
opportunistic, so some balances will be stale. A net worth figure built from
stale components is misleading unless the staleness is visible.

**Tests:** net worth reflects card outstanding as negative; stale components are
flagged; an account with no anchor and no balance-carrying message is excluded
with an explanation rather than treated as zero.

---

## Task 2.10 — Correctness dashboard

One screen answering: *can I trust these numbers?*

- Last reconciled timestamp per account
- Count of unmatched transfers
- Count of `PENDING_REVIEW` transactions
- Statement-vs-computed mismatch per card
- Accounts with stale balances

Not analytics — a health check. Phase 3's budgets are only as good as what this
screen reports.

---

## Exit criteria

**CI**
- [ ] Transfer detection tests green
- [ ] Card payment matching tests green
- [ ] Refund netting tests green
- [ ] Golden tests still pass (Phase 1 corpus must not regress)
- [ ] detekt clean

**Manual verification — the real bar**
- [ ] Pick one month. Compare Ledgerly's income and expense totals against the
      actual bank statement for that month. They should match.
- [ ] No card bill counted as an expense
- [ ] No self-transfer counted as an expense
- [ ] No wallet top-up counted as an expense
- [ ] Net worth matches a manual calculation

If the monthly totals don't match a real statement, Phase 2 isn't done. That
comparison is the whole point of this phase.
