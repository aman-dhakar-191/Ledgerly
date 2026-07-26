# Corpus findings

Derived from a 5,613-message inbox dump, July 2026. These are observations from
real data and override any general assumption in `docs/parser.md`.

---

## 0. Regulatory context (TRAI TCCCPR)

Indian A2P SMS headers are regulated, which makes several parser decisions safe
rather than heuristic.

**Structure:** `XY-HEADER-Z`
- `XY` — operator (X) + circle (Y). Routing only; discard for identity.
- `HEADER` — the registered entity (`ICICIT`, `SBIUPI`, `axioFS`). This is the
  institution.
- `-Z` — message-type suffix, mandatory since **6 May 2025**:
  `P` promotional, `S` service, `T` transactional (OTP-only for banks), `G`
  government. Assigned by the telco from the registered template, not by text.
  See `docs/parser.md` for how the pre-filter uses it. Messages before May 2025
  have no suffix — the corpus contains both.

**Templates:** every format a bank sends is pre-registered on the operators' DLT
platforms with variable slots marked `{#var#}` and fixed text preserved. The
templates are the regex patterns, conceptually — but they are **not public**
(visible only to the registering entity and telcos), so they cannot be bundled.
Constraints that are usable: **max 5 variables per template, each ≤30
characters** — applied as sanity bounds in rule validation (`docs/parser.md`).

**Prefix decode (X = operator, Y = circle)** — small, static, useful for
diagnostics (e.g. identifying which gateway dropped a message):

Operators: A Airtel · B BSNL · C V-CON · D Aircel · E Reliance Telecom ·
J Jio · M MTNL · Q Quadrant · R RCom · T Tata · V Vodafone-Idea

Circles: A AP · B Bihar · D Delhi · E UP-East · G Gujarat · H Haryana ·
I HP · J J&K · K Kolkata · L Kerala · M Mumbai · N North-East · O Orissa ·
P Punjab · R Rajasthan · S Assam · T TN/Chennai · V West Bengal · W UP-West ·
X Karnataka · Y MP · Z Maharashtra

So `AD-ICICIT-S` = Airtel / Delhi / ICICI / Service. This decode is optional
polish, not required for parsing.

The full public **header registry** (entity → legal name) is downloadable from
TRAI's Header Information Portal but is not bundled: it's huge, doesn't
distinguish bank-vs-card-vs-wallet (which the learned `SenderRegistry` does), and
goes stale. The learned registry is the right primitive.

---

## 1. Sender IDs identify the telecom route, not the institution

The same ICICI message format arrives from at least thirteen distinct sender IDs:

```
AD-ICICIT-S  AX-ICICIT-S  JD-ICICIT-S  JM-ICICIT-S  JX-ICICIT-S
VM-ICICIT-S  VK-ICICIT-S  CP-ICICIT-S  VA-ICICIT-S  TM-ICICIT-S
CV-ICICIT-S  JK-ICICIT-S  VD-ICICIT-S  ... and suffix-less variants
```

The leading two letters are the operator/aggregator; the trailing `-S`/`-P`/`-T`/`-G`
is the traffic class. Neither belongs to the bank.

**Consequence: `ParserRule` must key on institution, not `sender_id`.** A rule
learned from `AD-ICICIT-S` has to fire on `JX-ICICIT-S`.

```
normalizeSender("AD-ICICIT-S") -> "ICICIT"
normalizeSender("JX-ICICIT")   -> "ICICIT"
normalizeSender("ICICIT")      -> "ICICIT"
```

Strip a leading `^[A-Z]{2}-` and a trailing `-[A-Z]$`. Keep the raw sender on
`RawSms` for provenance; use the institution for rule lookup and trust.

This collapses ~500 senders to roughly 40 institutions.

**Schema change:** `SenderRegistry` gains `institution: String`;
`ParserRule.sender_id` becomes `ParserRule.institution`.

---

## 2. Balance availability is per-message-format, not per-institution

An earlier reading of this corpus concluded ICICI never sends balances. That was
wrong — it depends on the message format, and one institution uses several.

| Institution | Format | Balance? |
|---|---|---|
| ICICI | `Acct XX924 debited for Rs X on DATE; MERCHANT credited. UPI:ref` | **No** |
| ICICI | `Acc XX924 debited Rs. X on DATE InfoBIL*INFT*XXXX.Avl Bal Rs. Y` | **Yes** |
| ICICI | `Rs. X debited from ICICI Bank Acc XX924 on DATE VIN*MERCHANT. Bal Rs. Y` | **Yes** |
| ICICI | `Acc XX924 debited Rs. X on DATE NFS*CASH WDL*. Avb Bal Rs. Y` | **Yes** (`Avb`, not `Avl`) |
| SBI | `Your A/C XXXXX583840 Credited INR X ... Avl Bal INR Y-SBI` | **Yes** |
| SBI | `Dear UPI user A/C X3840 debited by X on date DATE trf to PAYEE Refno N` | **No** |

**Consequence: reconciliation is opportunistic, not per-account.** Most UPI
transactions cannot be verified. The periodic balance-carrying messages
(bill payments, card-network transactions, ATM withdrawals, cash deposits)
re-anchor the account.

`Account.reconcilable` is therefore wrong as modelled. Replace with: every
transaction carrying a balance triggers reconciliation and, on success, acts as
an implicit anchor. Drift between balance-carrying messages is invisible until
the next one arrives. Show account balances with a "last verified" timestamp.

Balance label variants to handle: `Avl Bal`, `Avb Bal`, `Bal`, `Available Balance`.

---

## 2a. Card bill payment — the transfer link, resolved

The bank-side debit exists and pairs exactly with the card-side credit.

```
Bank side (JM-ICICIT-S, 1784819886245):
ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*FGR6.
Avl Bal Rs. 8,611.98.

Card side (JX-ICICIT-S, 1784853082258):
Dear Customer, Payment of INR 2,170.00 has been received on your ICICI Bank
Credit Card Account 4xxx5001 on 23-JUL-26.Thank you.
```

**`InfoBIL*INFT*` is ICICI's bill-payment narration.** Every observed card
payment carries it, with a varying 4-character suffix (`FGR6`, `FFX5`, `FG17`,
`0011`, `FEY3`, `FC37`, `0010`).

Matching rule (Phase 2):
- bank debit has `InfoBIL*INFT*`
- card credit matches `Payment of INR {amt} ... Credit Card Account {acct}`
- equal amount, same date
- link as `Transfer(kind = CARD_PAYMENT)`; neither side is an expense

Note `InfoBIL*INFT*` also covers non-card bill payments, so the amount+date
match against a card-side credit is what confirms it.

---

## 3. Account masking is inconsistent within one bank

The same SBI account appears as:

```
XXXXX583840      (VM-CBSSBI-S)
XX3840           (AD-SBIPSG-S)
XXXXXXXX3840     (BX-SBIINB)
```

ICICI cards appear as `XX9001`, `XX5001`, `XX6001`, `4xxx5001`, `4xxx6001`,
`6xxx9001`, and bare `6001` / `5001` / `9001`.

**Match accounts on trailing digits only.** Extract the longest trailing digit
run; match on the last 4 (or fewer if that is all that is given). `XX924` yields
`924`, which must still match an account whose known last4 is `3924`.

---

## 4. OTP messages look exactly like transactions and are not transactions

```
798594 is One-Time Password for INR 585.28 transaction towards ZOMATO LIMI
using ICICI Bank Credit Card XX6001. OTPs are SECRET. DO NOT disclose
```

Amount, merchant, card, currency — every field a spend has. These are
pre-authorisations; the transaction may never complete. `AD-ICICIO-T` and
`AX-ICICIO-T` send almost nothing else.

**Hardcoded pre-filter, not a learned rule.** Any body matching
`One-Time Password|OTP` is classified `OTP` and never becomes a transaction.
This runs before rule matching.

Double-counting risk: the real spend SMS arrives separately for the same amount.

---

## 5. Five near-miss categories that must not become transactions

All of these contain a plausible amount, merchant, and account.

| Class | Discriminator | Real money? |
|---|---|---|
| Declined | `declined due to insufficient` / `is declined, as` | No |
| SI upcoming | `is due by` / `to be debited from` | No — future |
| SI processed | `successfully processed payment of` | **Yes** |
| SI failed | `could not be processed` | No |
| UPI AutoPay scheduled | `is scheduled on` | No — future |

Examples:

```
Transaction of INR 354.22 at UPI-62022539084 on ICICI Bank Credit Card XX9001
was declined due to insufficient credit limit. Available Credit Limit is INR 58.79

Payment of INR 299.00 towards Merchant Amazon to be debited from ICICI Bank
Credit Card 6001, as per Standing Instruction YEyZRiKCDG, is due by 12/06/2026.

We have successfully processed payment of USD 23.60 to Merchant Anthropic,
as per Standing Instruction YO773YgqaO on 12/06/2026 for ICICI Bank Credit Card 6001.

Dear Customer, payment of INR 1999.00 for Google Play for Standing Instructions
Y8dQwevrGU on your ICICI Bank Credit Card 5001 could not be processed.

Dear UPI User, UPI AutoPay for NETFLIX COM debit of Rs.199.00 is scheduled
on .30/03/25, ...@yapl. Please ensure sufficient balance in your account. -SBI
```

**The discriminator appears after the amount.** The extractor must scan the
entire body before deciding; stopping at the first amount match will misclassify
all five.

The "SI upcoming" and "AutoPay scheduled" classes are useful — they feed
`ScheduleRule` / `Contribution` in Phase 4 as expected future debits.

---

## 6. Statement, refund and EMI formats

```
ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com.
Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26.

Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00
by 23-Jul-26 towards ICICI Bank Credit Card XX5001.

AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on
13-JAN-26. Revised total due Rs 5,377.55, minimum due Rs .00

Dear Customer, your transaction of Rs 16,110.00 using ICICI Bank Credit Card
XX5001 has been converted into EMI on 17-10-25.
```

- Statement → not a transaction; sets card outstanding and due date
- Refund → nets against a prior spend (Phase 2)
- EMI conversion → the original spend already counted; future instalments must
  not double-count (Phase 7). Tag in Phase 1 so the data exists later.

**`Rs .00` and `Rs .30` — amounts with no leading zero.**
`Paise.fromRupeeString` must handle a bare leading decimal point.

---

## 7. Multi-currency is not hypothetical

```
We have successfully processed payment of USD 23.60 to Merchant Anthropic ...
Your transaction of USD 10.80 on ICICI Bank Credit Card XX6001 is declined ...
```

USD transactions already exist in this corpus. `Transaction.currency` is
required from Phase 1. No FX conversion needed yet — store the original currency
and amount, and exclude non-INR from INR totals rather than guessing a rate.

---

## 8. Known formats — seed corpus for golden tests

Institution `ICICIT` — account:

| Class | Skeleton | Bal |
|---|---|---|
| ACCT_DEBIT_UPI | `ICICI Bank Acct XX### debited for Rs {amt} on {date}; {merchant} credited. UPI:{ref}` | no |
| ACCT_CREDIT_UPI | `Dear Customer, Acct XX### is credited with Rs {amt} on {date} from {payer}. UPI:{ref}` | no |
| ACCT_DEBIT_BILL | `ICICI Bank Acc XX### debited Rs. {amt} on {date} InfoBIL*INFT*{code}.Avl Bal Rs. {bal}` | **yes** |
| ACCT_DEBIT_VIN | `Rs. {amt} debited from ICICI Bank Acc XX### on {date} VIN*{merchant}. Bal Rs. {bal}` | **yes** |
| ACCT_DEBIT_ATM | `ICICI Bank Acc XX### debited Rs. {amt} on {date} NFS*CASH WDL*. Avb Bal Rs. {bal}` | **yes** |

Institution `ICICIT` — card:

| Class | Skeleton |
|---|---|
| CARD_SPEND | `ICICI Bank Credit Card XX#### debited for INR {amt} on {date} for UPI-{ref}-{merchant}` |
| CARD_SPEND_LIMIT | `{cur} {amt} spent using ICICI Bank Card XX#### on {date} on {merchant}. Avl Limit: INR {limit}.` |
| CARD_PAYMENT | `Dear Customer, Payment of INR {amt} has been received on your ICICI Bank Credit Card Account {acct} on {date}.Thank you.` |
| STATEMENT | `ICICI Bank Credit Card XX#### Statement is sent to {email}. Total of Rs {amt} or minimum of Rs {min} is due by {date}.` |
| STATEMENT_ALT | `Pay Total Amount Due of Rs {amt} or Minimum Amount Due of Rs {min} by {date} towards ICICI Bank Credit Card XX####.` |
| SI_PROCESSED | `We have successfully processed payment of {cur} {amt} to Merchant {merchant}, as per Standing Instruction {id} on {date} for ICICI Bank Credit Card {acct}.` |
| REFUND | `{merchant} refund of Rs {amt} credited to ICICI Bank Credit Card XX#### on {date}. Revised total due Rs {due}, minimum due Rs {min}` |
| EMI_CONVERSION | `Dear Customer, your transaction of Rs {amt} using ICICI Bank Credit Card XX#### has been converted into EMI on {date}.` |

Institution `CBSSBI` / `SBIPSG` / `SBIINB` / `SBIUPI` / `SBIBNK`:

| Class | Skeleton | Bal |
|---|---|---|
| UPI_DEBIT | `Dear UPI user A/C X#### debited by {amt} on date {date} trf to {payee} Refno {ref}` | no |
| ACCT_CREDIT_CASH | `Your A/C XXXXX###### Credited INR {amt} on {date} -Deposited by Cash by SELF. Avl Bal INR {bal}-SBI` | **yes** |
| ACCT_DEBIT_CDM | `Your AC XXXXX###### Debited INR {amt} on {date} -CDM CHARGE DR. Avl Bal INR {bal}.-SBI` | **yes** |
| ACCT_CREDIT_NEFT | `Dear Customer, INR {amt} credited to your A/c No XX#### on {date} through NEFT with UTR {utr} by {payer}, INFO: {info}-SBI` | no |
| ACCT_CREDIT_IMPS | `Dear Customer, Your a/c no. XXXXXXXX#### is credited by Rs.{amt} on {date} by a/c linked to mobile {mob}-{payer} (IMPS Ref no {ref}).` | no |
| NACH_BOUNCE | `Dear Customer, ECS/NACH dishonored in Acc XXXXX###### due to insufficient funds. Rs.{amt} debited to account as return charges.-SBI` | no |
| CARD_AMC | `Dear Customer, Your A/C ending with #### has been debited for INR {amt} on {date} towards annual maintenance charges for your SBI Debit Card ending with ####` | no |
| AUTOPAY_SCHEDULED | `Dear UPI User, UPI AutoPay for {merchant} debit of Rs.{amt} is scheduled on .{date}, {vpa}. Please ensure sufficient balance in your account. -SBI` | n/a |

Institution `JUSPAY` — Amazon Pay wallet:

| Class | Skeleton | Bal |
|---|---|---|
| WALLET_DEBIT | `Your Apay Wallet balance is debited for INR {amt}. Reference Number is {ref}` | no |
| WALLET_DEBIT_ALT | `Your Amazon pay Wallet balance is debited for INR {amt}. Transaction Reference Number is {ref}` | no |
| WALLET_PAYMENT | `Payment of Rs {amt} using Apay Balance successful at merchant. Updated Balance is Rs {bal}` | **yes** |

Institution `ZOMATO` — Zomato Money wallet:

| Class | Skeleton | Bal |
|---|---|---|
| WALLET_PAYMENT | `Payment of Rs. {amt} from Zomato Money Balance is successful. Updated balance: Rs. {bal}.` | **yes** |

Institution `axioFS` — Amazon Pay Later (BNPL):

| Class | Skeleton |
|---|---|
| BNPL_SPEND | `Thank you for availing Pay Later credit of Rs{amt}. For more info click {url}` |
| BNPL_SPEND_EMI | `Thanks for availing Rs{amt} Pay Later credit. For more info on EMI, Rate of Interest & Tenure click {url}` |
| BNPL_BILL_DUE | `Your Pay Later bill of Rs {amt} will be debited on 5th of this month from registered bank a/c.` |
| BNPL_LIMIT_CHANGE | `Approved credit for your Pay Later account has been modified to Rs. {amt}.` |

Note: spend formats have **no space** after `Rs`; the bill format does.

Institution `EPFOHO` — EPF:

| Class | Skeleton |
|---|---|
| EPF_BALANCE | `Dear XXXXXXXX####, your passbook balance against {memberId} is Rs. {bal}/-. Contribution of Rs. {amt} for due month {month} has been received.` |

Institution `RZRPAY` — Razorpay subscriptions:

| Class | Skeleton |
|---|---|
| SUBSCRIPTION_PAID | `Your payment of Rs.{amt} for the subscription to {merchant} is successful.` |

Institution `YESBNK` — mandate notices (never transactions):

| Class | Skeleton |
|---|---|
| MANDATE_UPCOMING | `For the upcoming mandate set for {date}, your account will be debited with Rs.{amt} towards {merchant} for the Upi Mandate.` |
| COLLECT_REQUEST | `{merchant} has requested money from you on your AMAZON app. On approving the request, Rs.{amt} will be debited from your a/c.` |

---

## 9. Self-transfers between own accounts — high volume, high value

```
ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:...
ICICI Bank Acct XX924 debited for Rs 15000.00 on 07-Jul-26; AMAN DHAKAR credited. UPI:...
Dear UPI user A/C X3840 debited by 5000.00 on date 23Jul26 trf to AMAN DHAKAR Refno ...
Dear UPI user A/C X3840 debited by 8000 on date 05Jan26 trf to Aman  Dhakar Refno ...
```

The payee is the account holder's own name. These are SBI ↔ ICICI movements
between the user's own accounts — frequent, and often large (₹5,000–₹15,000).

Counting them as expenses inflates spending severely.

**Detection: a user-confirmed payee allowlist, not name inference.** Name
variants observed: `AMAN DHAKAR`, `Aman Dhakar`, `Aman  Dhakar` (double space).
Normalise case and collapse whitespace before matching.

**Do not infer from surname.** `KIRAN DHAKER`, `RAHUL DHAKAR` and
`KIRAN  DHAKER` also appear and are genuine outgoing transfers to family, not
internal movements. The allowlist must be explicitly confirmed by the user.

Where both sides are present (SBI debit + ICICI credit, or vice versa) with
equal amount and same date, link as `Transfer(kind = ACCOUNT_TO_ACCOUNT)`. Where
only one side appears, still mark `is_internal = true` on allowlist match.

---

## 10. Wallets and BNPL — account types beyond banks and cards

### Amazon Pay wallet — 72+ messages via `JUSPAY`

```
Your Apay Wallet balance is debited for INR 140.00. Reference Number is 600789415458.
Your Amazon pay Wallet balance is debited for INR 200.00. Transaction Reference Number is ...
```

No balance, no merchant, no account number. Funded from ICICI:

```
ICICI Bank Acct XX924 debited for Rs 500.00 on 02-Jun-25; Amazon Pay Bala credited. UPI:...
```

**Ignoring the wallet double-counts.** The ICICI top-up is a transfer into the
wallet; the wallet debit is the real expense. Model as a `WALLET` account.

Wording varies (`Apay Wallet` / `Amazon pay Wallet`, `Reference Number is` /
`Transaction Reference Number is` / `Transaction Reference Number`), and the
sender prefix varies across `JUSPAY` routes — further evidence for institution
normalisation.

**A third format carries a balance and is reconcilable:**
```
Payment of Rs 114.00 using Apay Balance successful at merchant.
Updated Balance is Rs 267.98 - SMS by Juspay
```
`Updated Balance is Rs {bal}` — treat as `balance_after` for the wallet account.

### Zomato Money — a fourth wallet

```
Payment of Rs. 14.41 from Zomato Money Balance is successful.
Updated balance: Rs. 0.00. Contact zomatomoneysupport@zomato.com for queries. -ZOMATO
```

Small amounts, carries a balance. Same `WALLET` treatment. Note the label is
`Updated balance:` (lowercase b, colon) versus Juspay's `Updated Balance is`.

### axio — Amazon Pay Later (BNPL). Fully parseable; not a blind spot.

An earlier reading concluded axio spends produce no SMS. That was wrong — the
search pattern missed them because axio says "availing Pay Later credit", not
"debited".

**Purchase — primary format**
```
Thank you for availing Pay Later credit of Rs656.7. For more info click {url}
To report misuse call 18009877678 -axio
```

**Purchase — EMI-eligible variant** (amount moves before the noun phrase)
```
Thanks for availing Rs4848.99 Pay Later credit. For more info on EMI, Rate of
Interest & Tenure click {url} -axio
```

**Monthly bill**
```
Your Pay Later bill of Rs 1698 will be debited on 5th of this month from
registered bank a/c. View Bill {url} -axio
```

**Credit limit change**
```
Approved credit for your Pay Later account has been modified to Rs. 30000.
Please ensure timely payments on/before the due date for revaluation -axio
```

**Amount formatting:** `Rs656.7`, `Rs199.0`, `Rs2804.0` — **no space after
`Rs`**, one or two decimals. The bill format uses `Rs 1698` *with* a space and no
decimals. Both must parse.

So axio is structurally a credit card: individual spends, a monthly statement, a
settlement debit from ICICI to `CAPITALFLOAT`. Model as a `BNPL` account.

**No merchant name on spends.** Only the amount. However, several correlate
exactly with adjacent SMS from other senders — `Rs199.0` beside a Vi recharge
confirmation, `Rs349.0` and `Rs579.0` beside Airtel recharge confirmations.
Merchant is recoverable by timestamp correlation, or simply left for manual
categorisation in the review inbox. Uncategorised by default, not missing.

**Two Pay Later account numbers appear:** `XXX0012` and `XXX7676`. Verify
whether both are live before assuming one account.

`axioLN`, `axioPL`, `axioCR`, `axioFN` are loan-marketing senders — classify as
PROMO. Only `axioFS` carries transactions.

---

## 11. Further formats

**Card spend — second format, carries available limit**
```
INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT.
Avl Limit: INR 15,468.00. If not you, call 1800 2662/SMS BLOCK 6001 to 9215676766.
```
```
USD 13.67 spent using ICICI Bank Card XX5001 on 19-Oct-25 on GITHUB, INC..
Avl Limit: INR 30,060.41.
```

**`Avl Limit` is not a balance.** It is remaining credit:
`outstanding = credit_limit - available_limit`. Feeding it to balance
reconciliation directly would be wrong. It reconciles the *card* once
`credit_limit` is known, which is a Phase 2 concern.

Note this format has a cleaner merchant field (`on BLINKIT`, `on GITHUB, INC..`)
than the UPI card format, and appears for both INR and USD.

**ATM withdrawal**
```
ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*.
Avb Bal Rs. 32,327.01.
```
`Avb`, not `Avl`. Transfer to a CASH account; the spend is invisible afterwards.

**NACH / ECS bounce charge**
```
Dear Customer, ECS/NACH dishonored in Acc XXXXX583840 due to insufficient funds.
Rs.295.00 debited to account as return charges.-SBI
```
A real fee. Also observed with `Rs.0.00`.

**Debit card annual charge**
```
Dear Customer, Your A/C ending with 3840 has been debited for INR 236.0 on
19-09-25 towards annual maintenance charges for your SBI Debit Card ending with 4517
```

**Standing instructions on a debit card** — not only credit cards:
```
your payment of INR 499.00 for saasguru to be debited from your ICICI Bank
Debit Card 7619, as per Standing Instructions Y1Mppqif0Z, is due by 11/09/2025
```

**Amazon collect request** — a request, not a debit. Must not parse:
```
SMARTWORKS TECH SOLUTIONS PVT has requested money from you on your AMAZON app.
On approving the request, Rs.140.00 will be debited from your a/c.
```
Discriminator: `has requested money from you`. Add to the non-transaction
pre-filter.

**SBI amount formatting is inconsistent**
```
debited by 2          (no decimals)
debited by 50.0       (one decimal)
debited by 1173.0
debited by 210.25     (two decimals)
debited by 49.66
```
The amount parser must accept 0, 1, or 2 decimal places, with or without commas.

---

## 12. EPF balance arrives by SMS — Phase 4

```
Dear XXXXXXXX6775, your passbook balance against APKKP23388350000010194 is
Rs. 7,050/-. Contribution of Rs. 2,350/- for due month Oct-24 has been received.
```

Sender family: `EPFOHO` (`BZ-EPFOHO`, `AX-EPFOHO`, `VA-EPFOHO-G`, `BV-EPFOHO-G`,
`AD-EPFOHO-S`, `JD-EPFOHO-S`, `BT-EPFOHO-G`, `BH-EPFOHO-G` — ~20 messages).

This is significant for Phase 4. The design assumed EPF was snapshot-only with
manual entry from the EPFO portal. It is not — the balance and the monthly
contribution both arrive automatically.

Maps directly to:
- `BalanceSnapshot(instrument = EPF, balance, source = STATEMENT_IMPORT)`
- `Contribution(expected_amount, status = CONFIRMED)`

Note the trailing `/-` on amounts, and that the UAN is masked while the member ID
is not.

**PPF:** no PPF messages found in this corpus. If PPF contributions are made by
transfer from ICICI, that debit will appear normally and can be matched to a
`ScheduleRule`. The PPF *balance* still requires manual entry.

---

## 13. Other senders worth classifying

Financial, appear in the corpus, not yet modelled:

| Sender family | Content | Phase |
|---|---|---|
| `CDSLTX`, `CDSLEV`, `NSESMS`, `NSEIPO`, `BSELTD`, `KFINCR` | demat, IPO allotment, corporate actions | 4 |
| `UPSTX` | broker | 4 |
| `ITDCPC`, `ITDEFL` | income tax | 7 |
| `axioLN`, `axioPL`, `axioCR`, `axioFN` | loan marketing | PROMO |
| `RZRPAY` | subscription payments via Razorpay | 1 |
| `SBIBNK` e-mandate failures | `Payment of Rs 499.00 for saasguru for e-mandate ... could not be processed` | pre-filter SI_FAILED |

The Razorpay format is a real transaction:
```
Your payment of Rs.999 for the subscription to TAGMANGO PRIVATE LIMITED is
successful. Any recurring subscription payments will be automatically charged
to your UPI from now - Razorpay
```
