package com.amandhakar.ledgerly.parser

/**
 * Tier 2 output (docs/parser.md): a suggestion, never a direct ledger write. Always paired with
 * `source = SMS_GENERIC`, `status = PENDING_REVIEW` by the caller.
 *
 * [currency] isn't in Task 1.6's original sketch but docs/parser.md's field table added it as
 * required once the corpus turned up USD messages — same after-the-fact extension `Paise` itself
 * just went through for the amount grammar.
 */
data class GenericExtraction(
    val amount: ExtractedField<Long>,
    val currency: ExtractedField<String>,
    val direction: ExtractedField<Direction>,
    val accountLast4: ExtractedField<String>,
    val balanceAfter: ExtractedField<Long>,
    val merchant: ExtractedField<String>,
    val occurredAt: ExtractedField<Long>,
    val reference: ExtractedField<String>,
    /** `Avl Limit` (docs/corpus-findings.md §8) - a credit card's available limit, never a balance. */
    val availableLimit: ExtractedField<Long> = ExtractedField.empty(),
)
