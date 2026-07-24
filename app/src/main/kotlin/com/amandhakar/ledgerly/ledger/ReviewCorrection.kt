package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.entity.Direction

/**
 * The review inbox's edited state for one [com.amandhakar.ledgerly.database.entity.Transaction]
 * (Task 1.13). A field left equal to what [com.amandhakar.ledgerly.parser.GenericExtractor]
 * originally produced is "confirmed as-is" and eligible to anchor rule generation; a changed field
 * is a correction the pipeline got wrong and must not be used to generate a rule from (docs/parser.md:
 * "over-specific... a rule that matches too broadly corrupts the ledger").
 */
data class ReviewCorrection(
    val amount: Long,
    val direction: Direction,
    val merchant: String?,
    val occurredAt: Long,
    val balanceAfter: Long?,
)
