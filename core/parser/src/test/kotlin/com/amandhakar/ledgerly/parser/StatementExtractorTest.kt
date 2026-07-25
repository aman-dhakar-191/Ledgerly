package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
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
    fun `an ordinary spend message has no statement amounts`() {
        val body = "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. Avl Limit: INR 15,468.00."

        assertThat(StatementExtractor.extractAmounts(body)).isNull()
    }
}
