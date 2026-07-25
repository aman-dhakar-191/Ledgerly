# Phase 3 — Budget

Read `CONTEXT.md`, `docs/schema.md`, and `docs/corpus-findings.md` first.

> **Written ahead of the data this phase depends on.**
> Phases 0–2 were specified against a real 5,613-message corpus. This one was
> not: categorisation quality depends on what merchant strings actually look
> like at volume, which is only observable once Phase 2 has been running.
>
> Expect the merchant-normalisation rules (Task 3.2) and the category taxonomy
> (Task 3.1) to need revision after a few weeks of real use. **Revise them
> rather than working around them** — a fragmented merchant map is very hard to
> repair later. If something here contradicts observed behaviour, the observation
> wins; raise it rather than forcing the spec.

**Goal:** the app becomes useful daily. Phase 2 made the numbers correct; this
makes them meaningful.

**In scope:** category taxonomy, merchant normalisation, learned merchant map,
monthly budgets, rollover, spend-vs-budget views.

**Out of scope:** investments (Phase 4), sync (Phase 5), LLM categorisation
(Phase 6 — rules and learning first), sinking funds and goals (Phase 7).

---

## Phase 2 gate

- [ ] One month's totals verified against a real bank statement
- [ ] No card bill, self-transfer, or wallet top-up counted as an expense

Budgets built on wrong totals are worse than no budgets — they produce confident
numbers that are silently incorrect. Do not start until this passes.

---

## Task 3.1 — Category taxonomy

Two levels, no deeper. Deeper taxonomies get abandoned.

```
Food          → Groceries, Dining, Delivery
Transport     → Fuel, Ride-hailing, Public, Parking
Housing       → Rent, Utilities, Maintenance
Shopping      → Clothing, Electronics, Household
Health        → Medical, Pharmacy, Fitness
Subscriptions → Streaming, Software, Telecom
Financial     → Fees, Interest, Insurance, Tax
Personal      → Grooming, Gifts, Entertainment
Income        → Salary, Refunds, Other
```

Seed with this; make it fully editable. `tax_section` on the category
(`80C`, `80D`) feeds Phase 7 and costs nothing now.

Uncategorised is a first-class state, not an error. Never force a category.

**Tests:** two-level constraint enforced; deleting a parent reassigns children
rather than orphaning them; `is_income` excludes from expense totals.

---

## Task 3.2 — Merchant normalisation

The load-bearing piece. Get it wrong and the merchant map fragments into
hundreds of near-duplicates that never accumulate enough weight to be useful.

From the corpus, the same merchant appears as:

```
SmartWorks Stor / SMARTWORKS TECH / Smartworks Tech / SmartWorks Store
CloudKitch Priv / Cloud Kitch Pri
Amazon Pay / Amazon Pay Bala / Amazon Pay Balan / Amazon Bill Pay
Blinkit / BLINKIT COMMERC / Blink Commerce / BLINK COMME / GROFERS IND
kwality Walls / Kwality Walls
ZOMATO / ZOMATO LIMITED / Zomato Limited / ZOMATO LIMI / ZOMATO CYBS
```

Note ICICI truncates merchant names to ~15 characters, so normalisation must
handle prefix-matching, not just case and whitespace.

```kotlin
fun normalizeMerchant(raw: String): String
```

Pipeline: uppercase → collapse whitespace → strip punctuation → strip corporate
suffixes (`PVT`, `LTD`, `LIMITED`, `INDIA`, `PRIVATE`) → collapse to a canonical
key.

**Truncation handling:** if a normalised string is a prefix of an existing key
(or vice versa) and at least 8 characters, treat as the same merchant, subject to
user confirmation the first time.

Aliases are a table, not code — the user must be able to merge two keys from the
UI when normalisation misses.

**Tests:** every group above collapses to one key; `BLINK COMME` matches
`BLINKIT COMMERCE` by prefix; `KIRAN DHAKER` and `RAHUL DHAKAR` stay distinct;
manual merge and split work.

---

## Task 3.3 — Merchant map with learning

```
MerchantMap
  normalized_key, category_id, weight, source (USER | LEARNED | SEED)
```

Three tiers, in order:
1. Exact key match → apply instantly
2. Prefix match above threshold → apply, flag as low-confidence
3. No match → uncategorised, surfaced for user assignment

Every user assignment writes to the map with `source = USER` and increments
weight. A `USER` entry always beats a `LEARNED` one.

Bulk assignment matters: "categorise all 47 CloudKitch transactions as
Food → Delivery" must be one action. Without it the initial categorisation pass
is unbearable.

**No LLM here.** Phase 6 adds a tier for genuinely novel merchants, and it needs
this baseline to measure against.

**Tests:** exact match applies; a user correction overrides a learned entry;
bulk assignment writes one map entry and updates all matching transactions;
retroactive application when a new mapping is added.

---

## Task 3.4 — Merchant-less transactions

Some sources carry no merchant at all:
- axio BNPL spends — amount only
- Amazon Pay wallet debits — amount and reference only
- Cash withdrawals — the spending is invisible

These cannot be auto-categorised, ever. Give them a dedicated review path with
amount, date and source, and let the user assign directly.

[Optional, if it proves useful] axio spends often correlate by timestamp with
other SMS — a `Rs199.0` axio spend beside a Vi recharge confirmation. Offer the
nearby message as a hint; never auto-apply it.

**Tests:** merchant-less transactions never receive an auto-category; they appear
in a distinct review queue.

---

## Task 3.5 — Budgets

```
Budget
  category_id, period, amount, rollover, active_from, active_to
```

- Monthly at category or subcategory level
- `rollover` — unspent carries forward or resets. Per-budget, not global.
- Multiple active periods (history is retained when an amount changes)

Budget consumption **excludes** `is_internal` transactions. This depends
entirely on Phase 2 having worked.

**Tests:** rollover accumulates and resets correctly; internal transfers never
consume budget; changing an amount mid-period doesn't corrupt history.

---

## Task 3.6 — Spend-vs-budget views

- Current month: spent, remaining, days left, projected end-of-month
- Per-category progress
- Trailing 3-month average per category, for setting realistic amounts
- Uncategorised total, prominently — an unnoticed uncategorised pile makes every
  other number wrong

Projection is arithmetic: known recurring debits (`ScheduleRule`) plus
category-average burn rate. No model needed.

---

## Task 3.7 — Initial categorisation pass

A one-time flow over existing uncategorised transactions, ordered by merchant
frequency so the highest-volume merchants get handled first.

From the corpus this means roughly: SmartWorks, CloudKitch, Amazon Pay, Blinkit,
kwality Walls, Zomato, EatClub, Idealprepaid — around 20 merchants covering the
majority of transactions.

Show progress ("312 of 1,847 categorised"). Allow stopping and resuming.

---

## Exit criteria

**CI**
- [ ] Merchant normalisation tests green, including every corpus group in 3.2
- [ ] Budget rollover and internal-exclusion tests green
- [ ] Phase 1 and 2 tests still pass

**Real use**
- [ ] After four weeks, >80% of new transactions auto-categorise correctly
- [ ] Uncategorised backlog is stable or shrinking, not growing
- [ ] Monthly category totals look right against your own sense of spending

If auto-categorisation sits below 80%, the problem is almost always merchant
normalisation (Task 3.2), not the map. Fix normalisation before adding tiers.
