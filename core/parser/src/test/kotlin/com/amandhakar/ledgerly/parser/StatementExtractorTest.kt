package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

/** Task 2.4's own test: "both statement formats parse." */
class StatementExtractorTest {

    @Test
    fun `the 'Total of Rs X or minimum of Rs Y' format parses both amounts`() {
        val body = "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
            "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26."

        val amounts = StatementExtractor.extractAmounts(body)

        assertThat(amounts).isEqualTo(StatementAmounts(totalDue = 1_039_194L, minimumDue = 52_000L))
    }

    @Test
    fun `the 'Pay Total Amount Due' format parses both amounts`() {
        val body = "Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00 " +
            "by 23-Jul-26 towards ICICI Bank Credit Card XX5001."

        val amounts = StatementExtractor.extractAmounts(body)

        assertThat(amounts).isEqualTo(StatementAmounts(totalDue = 694_121L, minimumDue = 217_000L))
    }

    @Test
    fun `axio's single-amount BNPL bill format parses with total equal to minimum`() {
        val body = "Your Pay Later bill of Rs 1698 will be debited on 5th of this month from " +
            "registered bank a/c. View Bill http://example.com -axio"

        val amounts = StatementExtractor.extractAmounts(body)

        assertThat(amounts).isEqualTo(StatementAmounts(totalDue = 169_800L, minimumDue = 169_800L))
    }

    @Test
    fun `an ordinary spend message has no statement amounts`() {
        val body = "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. Avl Limit: INR 15,468.00."

        assertThat(StatementExtractor.extractAmounts(body)).isNull()
    }

    @Test
    fun `axio's 'of this month' due date resolves against the received month, ignoring any fallback`() {
        val body = "Your Pay Later bill of Rs 1698 will be debited on 5th of this month from " +
            "registered bank a/c. View Bill http://example.com -axio"
        val receivedAt = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()

        val dueDate = StatementExtractor.extractDueDate(body, fallbackOccurredAt = 999L, receivedAt = receivedAt)

        val expected = Instant.parse("2026-07-05T00:00:00Z")
            .atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(dueDate).isEqualTo(expected)
    }

    @Test
    fun `a statement format with a literal date falls back to the caller-supplied occurred-at`() {
        val body = "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
            "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26."

        val dueDate = StatementExtractor.extractDueDate(body, fallbackOccurredAt = 12_345L, receivedAt = 0L)

        assertThat(dueDate).isEqualTo(12_345L)
    }
}
