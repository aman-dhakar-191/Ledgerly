package com.amandhakar.ledgerly.parser

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * docs/corpus-findings.md §9: self-transfers appear as ordinary UPI payments to the account
 * holder's own name, and the same person shows up as `AMAN DHAKAR`, `Aman Dhakar`, `Aman  Dhakar`
 * (double space) across different messages. Uppercase and collapse whitespace so all of those
 * match one `PayeeAllowlist` entry — but never infer a match from a normalized name alone without
 * an explicit prior user confirmation: `KIRAN DHAKER` and `RAHUL DHAKAR` are genuine outgoing
 * transfers to family, not the account holder, despite the surname resemblance.
 */
fun normalizePayeeName(raw: String): String = raw.trim().uppercase().replace(WHITESPACE_RUN, " ")
