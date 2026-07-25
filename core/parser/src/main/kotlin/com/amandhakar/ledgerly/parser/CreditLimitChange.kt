package com.amandhakar.ledgerly.parser

import com.amandhakar.ledgerly.model.money.Paise

/**
 * Task 2.6/docs/corpus-findings.md §10's axio BNPL_LIMIT_CHANGE ("Approved credit for your Pay
 * Later account has been modified to Rs. 30000.") - not a transaction, just the new credit_limit.
 */
private val CREDIT_LIMIT_CHANGE_AMOUNT = Regex("""(?i)has been modified to rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})""")

fun extractNewCreditLimit(body: String): Long? {
    val match = CREDIT_LIMIT_CHANGE_AMOUNT.find(body) ?: return null
    return Paise.fromRupeeString(match.groupValues[1])?.value
}
