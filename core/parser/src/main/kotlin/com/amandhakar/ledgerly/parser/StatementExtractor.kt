package com.amandhakar.ledgerly.parser

import com.amandhakar.ledgerly.model.money.Paise
import java.time.Instant
import java.time.ZoneId

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

    /** axio's BNPL_BILL_DUE (docs/corpus-findings.md §10) - one amount, no separate minimum due. */
    private val SINGLE_BILL_AMOUNT = Regex("""(?i)pay later bill of rs\.?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})""")

    /**
     * axio's BNPL_BILL_DUE names its due date as "5th of this month" rather than a literal calendar
     * date (docs/corpus-findings.md §10/tasks/phase-2.md's Task 2.6) - [GenericExtractor]'s
     * `DATE_PATTERN` can never match this phrasing, so it needs its own resolution against the
     * message's own received month.
     */
    private val DAY_OF_THIS_MONTH = Regex("""(?i)on\s+(\d{1,2})(?:st|nd|rd|th)\s+of\s+this\s+month""")

    fun extractAmounts(body: String): StatementAmounts? =
        TOTAL_AND_MINIMUM.firstNotNullOfOrNull { pattern -> matchTotalAndMinimum(body, pattern) }
            ?: matchSingleBillAmount(body)

    /**
     * The due date for most statement formats is an ordinary [GenericExtractor] date field, already
     * computed by the caller and passed as [fallbackOccurredAt]; only axio's "of this month" phrasing
     * needs resolving here, against [receivedAt]'s own calendar month.
     */
    fun extractDueDate(body: String, fallbackOccurredAt: Long?, receivedAt: Long): Long? {
        val day = DAY_OF_THIS_MONTH.find(body)?.groupValues?.get(1)?.toIntOrNull()
        return if (day != null) dueDateForDayOfMonth(day, receivedAt) else fallbackOccurredAt
    }

    private fun dueDateForDayOfMonth(day: Int, receivedAt: Long): Long? {
        val zone = ZoneId.systemDefault()
        val receivedDate = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val dueDate = runCatching { receivedDate.withDayOfMonth(day) }.getOrNull() ?: return null
        return dueDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun matchTotalAndMinimum(body: String, pattern: Regex): StatementAmounts? {
        val match = pattern.find(body) ?: return null
        val total = Paise.fromRupeeString(match.groupValues[1])?.value ?: return null
        val minimum = Paise.fromRupeeString(match.groupValues[2])?.value ?: return null
        return StatementAmounts(total, minimum)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun matchSingleBillAmount(body: String): StatementAmounts? {
        val match = SINGLE_BILL_AMOUNT.find(body) ?: return null
        val amount = Paise.fromRupeeString(match.groupValues[1])?.value ?: return null
        return StatementAmounts(totalDue = amount, minimumDue = amount)
    }
}
