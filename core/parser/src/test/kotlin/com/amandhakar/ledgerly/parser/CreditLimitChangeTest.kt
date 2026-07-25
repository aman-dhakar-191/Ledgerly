package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CreditLimitChangeTest {

    @Test
    fun `the new credit limit is extracted from the modification notice`() {
        val body = "Approved credit for your Pay Later account has been modified to Rs. 30000. " +
            "Please ensure timely payments on/before the due date for revaluation -axio"

        assertThat(extractNewCreditLimit(body)).isEqualTo(3_000_000L)
    }

    @Test
    fun `an ordinary spend message has no credit limit change`() {
        val body = "Thank you for availing Pay Later credit of Rs656.7. For more info click http://example.com -axio"

        assertThat(extractNewCreditLimit(body)).isNull()
    }
}
