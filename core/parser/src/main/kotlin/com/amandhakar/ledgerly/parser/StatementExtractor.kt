package com.amandhakar.ledgerly.parser

import com.amandhakar.ledgerly.model.money.Paise

/**
 * Task 2.4/docs/corpus-findings.md §6: a statement carries two amounts with different meanings
 * (total vs minimum due), never a transaction amount+direction - a distinct shape from anything
 * [GenericExtractor] handles. The due date and card's last4 are still ordinary [GenericExtractor]
 * fields, so callers pair this with [GenericExtractor.extract] rather than duplicating that logic.
 */
data class StatementAmounts(val totalDue: Long, val minimumDue: Long)

object StatementExtractor {

    private val TOTAL_AND_MINIMUM = listOf(
        Regex(
            """(?i)total of rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})\s*or\s*""" +
                """minimum of rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})""",
        ),
        Regex(
            """(?i)total amount due of rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})\s*or\s*""" +
                """minimum amount due of rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})""",
        ),
    )

    fun extractAmounts(body: String): StatementAmounts? =
        TOTAL_AND_MINIMUM.firstNotNullOfOrNull { pattern -> matchAmounts(body, pattern) }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun matchAmounts(body: String, pattern: Regex): StatementAmounts? {
        val match = pattern.find(body) ?: return null
        val total = Paise.fromRupeeString(match.groupValues[1])?.value ?: return null
        val minimum = Paise.fromRupeeString(match.groupValues[2])?.value ?: return null
        return StatementAmounts(total, minimum)
    }
}
