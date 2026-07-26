# Add-on — TRAI header suffix classification

**Status: retrofit.** Phase 1 shipped before this was known. It is a refinement,
not a missing Phase 1 task — the parser already works without it. Slot it in
whenever convenient during Phase 2; it doesn't block anything.

Read the updated §0 of `docs/corpus-findings.md` and the sender-normalisation
section of `docs/parser.md` for the full context.

---

## What this adds

Indian A2P SMS headers carry a regulator-assigned message-type suffix since
6 May 2025: `-P` promotional, `-S` service, `-T` transactional (OTP-only for
banks), `-G` government. It's assigned by the telco from the registered template,
so it's a more reliable signal than keyword matching — for the messages that have
it. Pre-May-2025 messages don't, and the corpus contains both.

The win: `-P` and `-T` messages essentially never carry a ledger transaction, so
they can be filtered before any body analysis. This reduces false positives and
saves work, but the existing body-based pre-filter already catches these, so it's
an optimisation and a robustness improvement, not a correctness fix.

---

## Changes

### 1. Parse and store the suffix

`RawSms` gains `sender_suffix: String?` (schema updated). Change sender parsing
from stripping the suffix to capturing it:

```kotlin
data class ParsedSender(val institution: String, val suffix: Char?)
fun parseSender(raw: String): ParsedSender  // "AD-ICICIT-S" -> ("ICICIT", 'S')
```

This is a schema change → Room migration (add nullable column, no backfill
needed; existing rows get null, which is correct for their era).

Backfill is optional: re-deriving the suffix for archived `RawSms` from the raw
sender is cheap and makes historical diagnostics consistent, but nothing depends
on it.

### 2. Use the suffix as step 0 of the pre-filter

```kotlin
fun classify(suffix: Char?, body: String): ParseClass
```

- `-P` → PROMO, stop
- `-T` → OTP, stop
- `-S`, `-G`, null → fall through to the existing body-based classification

The body checks stay exactly as they are — they're what handles the suffix-less
majority of the archive and remain the fallback for `-S`/`-G`.

### 3. Apply DLT bounds in rule validation

Registered templates permit at most 5 variables, each ≤30 characters. Add to the
rule validator: reject a generated rule with >5 capture groups, or a group that
routinely matches >30 characters. This catches greedy `(.+?)` groups that swallow
trailing text.

---

## Tests

- `parseSender` splits institution and suffix correctly; suffix-less input yields
  null
- `-P` classifies as PROMO without reading the body
- `-T` classifies as OTP
- a suffix-less pre-2025 OTP is still caught by the body check (regression guard —
  this must not break)
- migration preserves all existing `RawSms` rows, new column defaults null
- rule validator rejects a 6-capture-group rule

---

## What NOT to do

- Don't rely on the suffix alone — it's message *type*, not
  bank/card/wallet, and half the archive predates it.
- Don't remove or weaken the body-based pre-filter. The suffix narrows the work;
  the body checks are still the floor.
- Don't bundle the TRAI header registry or attempt to fetch DLT templates — see
  `docs/corpus-findings.md` §0 for why neither is worth it.
