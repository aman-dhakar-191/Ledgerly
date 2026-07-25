package com.amandhakar.ledgerly.parser

/**
 * Task 2.5/docs/corpus-findings.md §10: a bank debit funding an Amazon Pay wallet top-up is
 * visible only from the bank side - no corresponding wallet-side "credited" SMS exists in the
 * corpus - so this is a one-sided transfer (docs/schema.md), same treatment as
 * [com.amandhakar.ledgerly.database.entity.PayeeAllowlist]'s self-transfers: mark it
 * `is_internal`, never build a two-legged `Transfer` for a leg that was never observed.
 *
 * Hardcoded, not learned (same as the pre-filter's OTP/DECLINED classes) - this merchant string is
 * a specific, known brand, not something a user should have to confirm once per wording variant.
 * Observed variants (`Amazon Pay`, `Amazon Pay Bala`, `Amazon Pay Balan`) are truncations of the
 * same underlying string, so a prefix match covers all of them without listing each one.
 */
private val WALLET_FUNDING_PREFIXES = listOf("amazon pay", "amazon bill pay")

fun isWalletFundingMerchant(merchant: String?): Boolean {
    if (merchant == null) return false
    val normalized = merchant.trim().lowercase()
    return WALLET_FUNDING_PREFIXES.any { normalized.startsWith(it) }
}
