# Phase 4 — Investments

Read `CONTEXT.md`, `docs/schema.md`, and `docs/corpus-findings.md` §12 first.

> **Written ahead of the data this phase depends on.**
> The corpus contains EPFO and broker senders (`CDSLTX`, `NSESMS`, `BSELTD`,
> `KFINCR`, `UPSTX`) but those messages were not analysed in detail — only their
> existence was confirmed. Before starting, dump and inspect them the same way
> the bank formats were analysed, and add findings to `docs/corpus-findings.md`.
>
> The EPF format in §12 is verified. Everything else in this phase is designed
> from general knowledge and should be checked against real messages first.

**Goal:** know what you own, not just what you spend.

**In scope:** Instrument/Holding/BalanceSnapshot, EPF and PPF, ScheduleRule,
Contribution matching, price feed for market instruments.

**Out of scope:** sync (Phase 5), LLM (Phase 6), loans and tax summaries
(Phase 7).

---

## Phase 3 gate

- [ ] Auto-categorisation above 80%
- [ ] Uncategorised backlog stable

---

## Task 4.1 — Instrument types

Three genuinely different things. Forcing them into one model produces fake data.

| Type | Example | Value from | SMS role |
|---|---|---|---|
| `MARKET` | stocks, MF, ETF | units × latest price | buy/sell confirmation |
| `STATEMENT` | PPF, EPF | latest `BalanceSnapshot` | contribution debit only |
| `CASHFLOW` | RD, endowment | Σ confirmed contributions | payment confirmation |

PPF and EPF have no units and no price — the balance *is* the fact. Modelling
them as units × NAV forces invented numbers.

Portfolio value is three code paths, not one. Do not unify them.

**Tests:** each type computes value by its own path; a `STATEMENT` instrument
with no snapshot reports "unknown", never zero.

---

## Task 4.2 — EPF

Per `docs/corpus-findings.md` §12. **Arrives by SMS** — the earlier assumption
that this needed manual portal entry was wrong.

```
Dear XXXXXXXX6775, your passbook balance against APKKP23388350000010194 is
Rs. 7,050/-. Contribution of Rs. 2,350/- for due month Oct-24 has been received.
```

Each message yields both:
- `BalanceSnapshot(balance, source = STATEMENT_IMPORT)`
- `Contribution(expected_amount, status = CONFIRMED)`

Note the trailing `/-` on amounts (already handled by `Paise`), and that the UAN
is masked while the member ID is not.

**Interpolation between snapshots is optional and must be visually distinct.**
Never present an estimate as a known balance. If interpolating, mark it
`source = INTERPOLATED` and render it differently.

EPF contains employer share, employee share and annual interest — the salary SMS
shows none of these. Do not attempt to derive contributions from salary.

**Tests:** format parses to both entities; interpolated values are flagged;
missing months don't produce false balances.

---

## Task 4.3 — PPF

No PPF messages in the corpus. Manual balance entry.

Contributions, if made by transfer from ICICI, will parse as normal debits and
can be matched to a `ScheduleRule` (Task 4.5). The *balance* still requires
manual entry after checking the portal.

Prompt quarterly for a balance update rather than annually — PPF interest is
credited yearly but a stale balance for twelve months is misleading in net worth.

---

## Task 4.4 — Market instruments

**Analyse the broker SMS first.** `CDSLTX`, `CDSLEV`, `NSESMS`, `NSEIPO`,
`BSELTD`, `KFINCR`, `UPSTX` appear in the corpus. Determine what they actually
contain — holdings statements, trade confirmations, IPO allotments, corporate
actions — before designing around them.

Manual holdings entry regardless: `instrument`, `units`, `avg_cost`.

**Price feed:** needed for current value. Options, in order of preference for a
personal app:
1. Manual periodic entry — no dependency, works offline, fine for monthly review
2. A free quote API — adds a network dependency and a key to manage

[Build option 1 first. Only add a feed if manual entry proves annoying in
practice.]

`PriceSnapshot` stores what was used, so historical valuations stay reproducible.

---

## Task 4.5 — ScheduleRule and Contribution matching

```
ScheduleRule    → generates PENDING Contribution rows ahead of the due date
Contribution    → matched against actual transactions
```

Matching, per `docs/schema.md`:
- amount within 1%
- date within ±3 days
- narration contains the instrument's `payee_token`

Two of three required. No match by `due_date + 5 days` → `MISSED`, surfaced in
review.

**The `payee_token` is the fragile part.** SIP debits often show as `ACH-BSE-XXXX`
or a truncated fund name with no obvious link to the instrument. Expect to
hand-map a token per instrument the first time each one fires; after that it's
stable.

The corpus already contains a working example — the SBI UPI mandate to `AXIO`
and `UPSTOX SECURITIE` debits — so this mechanism is testable against real data.

**Tests:** matching debit confirms a pending contribution; a near-miss amount
outside 1% does not match; missed contributions flag after the grace period.

---

## Task 4.6 — Portfolio view

- Total portfolio value, by instrument type
- Per-instrument: current value, invested amount, absolute and percent return
- Contribution history and upcoming schedule
- Every value carries `as_of` — stale prices and interpolated balances marked

Net worth (Phase 2) now includes instruments.

**Do not compute XIRR or annualised returns yet.** They need clean contribution
histories, which you won't have until this has run for a while. Absolute return
is honest and sufficient.

---

## Exit criteria

**CI**
- [ ] Three value paths tested independently
- [ ] EPF format parses to snapshot + contribution
- [ ] Contribution matching tests green

**Real use**
- [ ] EPF balance matches the EPFO portal
- [ ] Market holdings match the broker statement
- [ ] SIP debits auto-confirm without manual intervention
- [ ] Portfolio total is a number you'd act on
