package com.amandhakar.ledgerly.parser

/**
 * Task 2.3/docs/schema.md: `Account.current_balance` is already documented as "negative for
 * liabilities," so a CREDIT_CARD account's outstanding balance is just `-current_balance` - the
 * existing signed-sum [reconcile] arithmetic (DEBIT subtracts, CREDIT adds) already gives a card
 * spend the correct "increases outstanding" effect and a card payment/refund the correct
 * "decreases outstanding" effect, with no separate accounting path needed.
 *
 * The one genuinely new signal a credit card contributes is `Avl Limit` (docs/corpus-findings.md
 * §8's `CARD_SPEND_LIMIT` format) - it reconciles to a balance only via the card's own
 * `credit_limit`, so it must never be fed into bank-account reconciliation, which expects a
 * literal stated balance, not a limit.
 */
fun outstandingFromAvailableLimit(creditLimit: Long, availableLimit: Long): Long = creditLimit - availableLimit
