package com.amandhakar.ledgerly.parser

/**
 * Task 2.6/docs/corpus-findings.md §10: axio settles its monthly bill via a direct debit from the
 * user's bank to `CAPITALFLOAT` (axio's settlement entity) - axio itself sends no confirmation SMS
 * for this, so unlike [isWalletFundingMerchant] there is no second leg to ever expect; the bank
 * debit alone is `Transfer(kind = CARD_PAYMENT)`, one-sided from the start (docs/schema.md).
 * Hardcoded, not learned - "CAPITALFLOAT" is a specific, known entity name, not a user preference.
 */
fun isBnplSettlementMerchant(merchant: String?): Boolean {
    if (merchant == null) return false
    return merchant.trim().lowercase() == "capitalfloat"
}
