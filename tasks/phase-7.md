# Phase 7 — Liabilities and planning

Read `CONTEXT.md`, `docs/schema.md`, and `docs/corpus-findings.md` §6 first.

> **Dependency note.** EMI and loan formats were observed in the corpus but not
> analysed in depth. Before starting, dump the relevant senders and add findings
> to `docs/corpus-findings.md`, as was done for the bank formats.
>
> Confirmed present: ICICI EMI conversion (§6), axio BNPL EMI-eligible spends,
> SBI debit card AMC charges, NACH bounce charges. Personal loans may not apply
> at all — check before building.

**Goal:** the parts of a complete financial picture that aren't day-to-day
spending.

**In scope:** loans with amortisation, card EMI, sinking funds, tax tagging,
goals, emergency fund.

Tasks here are **independent**. Build only what applies; skip the rest without
consequence.

---

## Phase 6 gate

None. Phase 6 is optional; Phase 7 can follow Phase 5 directly.

---

## Task 7.1 — Card EMI

Confirmed in the corpus:
```
Dear Customer, your transaction of Rs 16,110.00 using ICICI Bank Credit Card
XX5001 has been converted into EMI on 17-10-25.
```

**The double-count.** The original ₹16,110 spend was already recorded. The
monthly instalments that follow are repayment of that same amount, not new
spending. Counting both inflates expenses by the full purchase price.

- On conversion, flag the original transaction `converted_to_emi = true`
- Subsequent instalments post as: principal → transfer (debt reduction),
  interest → expense
- Never as new spend

`CardEmi` holds `original_txn_id`, `principal`, `tenure`, `rate`, `monthly_amount`.

**Tests:** conversion flags the original; instalments don't double-count; total
interest across the tenure is a real expense.

---

## Task 7.2 — Loans and amortisation

Only if applicable — check whether any loans exist before building.

```
Loan            principal, rate, tenure_months, emi_amount, start_date, account_id
AmortSchedule   per-instalment principal/interest split
```

**An EMI is not an expense.** Part of it reduces debt (a transfer against the
liability), part is interest (a real expense). Treating the whole EMI as an
expense overstates spending and understates net worth improvement.

Loan balance is a liability in net worth.

**Tests:** amortisation schedule sums to principal plus total interest; an EMI
debit splits correctly; loan balance decreases by principal only.

---

## Task 7.3 — Sinking funds

Non-monthly expenses amortised across months. Without this, one month looks
catastrophic and eleven look artificially good.

Candidates from the corpus: SBI debit card AMC (₹236 annual), insurance premiums,
card annual fees.

```
SinkingFund   name, annual_amount, due_month, category_id, accrued
```

Monthly accrual is a *virtual* budget allocation, not a transaction. When the
real expense hits, it draws against the accrued balance rather than blowing the
month's budget.

**Tests:** monthly accrual accumulates; the real expense draws down rather than
counting fresh; underfunded expenses flag before the due date.

---

## Task 7.4 — Tax tagging

`Category.tax_section` already exists from Phase 3. This surfaces it.

- 80C: PPF, EPF, ELSS, life insurance premiums
- 80D: health insurance
- 80TTA: savings interest

Annual summary by section, with the applicable ceiling and remaining headroom.

The data is already there — this is a view, not new collection. Note that
`ITDCPC` and `ITDEFL` senders appear in the corpus and may carry useful
information; worth checking.

**This is not tax advice.** It reports what was contributed under each tag. State
that plainly in the UI.

---

## Task 7.5 — Goals and emergency fund

```
Goal   name, target_amount, target_date, linked_account_id, current_amount
```

Emergency fund is a specific goal type: target expressed as *months of expenses*
rather than an absolute amount, computed from the trailing 3-month average
outflow (already available from Phase 6's runway calculation, or trivially
derivable without it).

Progress against target, projected completion at the current savings rate.

Keep this simple. Elaborate goal tracking is a feature people configure once and
never revisit.

---

## Task 7.6 — Complete net worth

Everything now in one number:

```
NetWorth = bank accounts + cash + wallets
         + instruments (Phase 4)
         − card outstanding (Phase 2)
         − BNPL outstanding (Phase 2)
         − loan balances (7.2)
```

Trend over time — monthly snapshots so the trajectory is visible, which matters
more than the absolute figure.

**Every component carries its `as_of`.** A net worth figure assembled from
components last verified at different times needs that visible, or it's a
confident number that's quietly wrong.

---

## Exit criteria

Per task, since these are independent:

- [ ] 7.1 EMI instalments don't double-count converted purchases
- [ ] 7.2 EMI splits into principal and interest correctly (if loans apply)
- [ ] 7.3 Annual expenses don't spike a single month
- [ ] 7.4 Section totals match actual contributions
- [ ] 7.5 Emergency fund target reflects real expenses
- [ ] 7.6 Net worth matches a manual calculation, with staleness visible

---

## After Phase 7

The app is complete against the original requirements. Anything further —
statement PDF import, Account Aggregator integration, shared expenses,
multi-currency FX — is in `docs/phases.md` under deferred, and should be judged
on whether you actually miss it after living with the app for a few months.

The most likely honest answer is that you don't.
