# Phase 6 — Intelligence

Read `CONTEXT.md` (invariant 3 especially) and `docs/parser.md` first.

> **Dependency note.** This phase is measured against baselines that only exist
> after Phases 1–3 have run for a while: parser miss rate, categorisation
> accuracy, review-inbox volume. **Record those numbers before building
> anything here.** Without a baseline you cannot tell whether a tier helps, and
> "it feels smarter" is not a measurement.
>
> Every task in this phase is optional. If the rules-only baseline is already
> good enough, skip it. That is a legitimate outcome, not a failure.

**Goal:** narrow, measurable improvements where rules genuinely cannot work.

**Architectural rule, non-negotiable:**

```
Deterministic core  →  always runs, fully testable
Intelligence layer  →  suggests, never writes silently
```

No model output ever mutates the ledger directly. Everything lands as a
suggestion with confidence, in the review inbox, until confirmed. A bad
extraction that writes silently corrupts net worth and surfaces three months
later.

---

## Phase 5 gate

- [ ] Full restore test passed

---

## Task 6.0 — Baselines

Before any model work, measure and record:

- Parser miss rate: messages reaching review per week
- Categorisation accuracy: auto-categorised correctly ÷ total
- Review inbox volume: items per week
- Time to categorise a new merchant

Each subsequent task must beat its baseline or be deleted. Write the numbers into
this file when you have them.

---

## Task 6.1 — LLM parser fallback

Highest value, lowest volume. Invoked once per *new format*, not per message.

- Only for an institution with no matching rule
- **Only on explicit user tap** in the review inbox, never automatically
- Strip account numbers and names before the request
- Output is a suggestion — same status as the generic extractor
- Purpose is to **generate a rule**, which then handles all future instances

The user confirms, a rule is generated and validated against the archive
(existing Phase 1 machinery), and the LLM is not called again for that format.

**Privacy.** This punches a hole in the local-first design you chose
deliberately. Mitigations in order: strip identifiers; make it opt-in per call;
prefer on-device inference if the device supports it. [Likely: on-device models
handle this adequately on recent Pixels/Samsungs — check the actual device before
assuming an API call is needed.]

**Beats baseline if:** time-to-handle a new bank format drops meaningfully.
If new formats appear twice a year, this is not worth building.

---

## Task 6.2 — Categorisation tier

Extends Phase 3's map with a third tier for genuinely novel merchants.

```
1. Exact merchant match        (Phase 3)
2. Prefix / fuzzy match        (Phase 3)
3. Model suggestion            (this task)
```

- Only reached when tiers 1 and 2 miss
- Send the normalised merchant string only — never amounts, dates, or account
  details
- Output is a suggestion; user confirmation writes to the map as `USER`
- Volume decays: once the map is trained, this tier goes nearly silent

**Beats baseline if:** categorisation accuracy improves by a measurable margin
over the Phase 3 exit number. If Phase 3 already reaches 90%, the remaining
headroom may not justify the dependency.

---

## Task 6.3 — Anomaly detection

**Statistical, not ML.** Rolling mean and standard deviation per category.

- Flag transactions beyond 2σ for that category
- Flag categories whose monthly total is beyond 2σ of trailing average
- Require at least 3 months of history before flagging anything

"Dining is 3× your usual this month" needs arithmetic, not a model.

**Tests:** synthetic data produces expected flags; insufficient history produces
none.

---

## Task 6.4 — Forecasting

Also arithmetic.

```
projected_month_end = spent_so_far
                    + known recurring debits remaining (ScheduleRule)
                    + (category daily average × days remaining)

runway = liquid balance ÷ trailing 3-month average outflow
```

Runway is probably the single most useful number in the app. It needs no model.

**Tests:** projection with a full month of history; a month with no history
degrades gracefully rather than projecting zero.

---

## Task 6.5 — Insight narration

The one place a model does something rules cannot.

- Feed **aggregates only** — category totals, deltas, budget status. Never raw
  transactions.
- Output is display text; it never writes to the ledger
- Explicitly opt-in

Example input: `Food: ₹8,400 spent, ₹6,000 budget, 9 days left, delivery share
up 40% vs 3-month average.`

Build last. Most fun to demo, least load-bearing. Delete it without hesitation if
the output is generic.

---

## Exit criteria

Each tier is judged independently against its Task 6.0 baseline:

- [ ] 6.1 measurably reduces time-to-handle a new format — or is deleted
- [ ] 6.2 measurably improves categorisation accuracy — or is deleted
- [ ] 6.3 flags real anomalies without excessive false positives
- [ ] 6.4 projections land within a reasonable margin of actual month-end
- [ ] 6.5 produces insights worth reading — or is deleted

**Invariant check:**
- [ ] No model output has ever written to the ledger without confirmation
- [ ] No raw transaction data has left the device
- [ ] Every model call is opt-in

Deleting a tier that doesn't beat its baseline is the correct outcome, not a
failure. The rules-only system is the product; this layer is an optimisation.
