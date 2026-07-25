package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Task 2.3's own test: "Avl Limit back-computes outstanding correctly given a known limit." */
class CreditCardOutstandingTest {

    @Test
    fun `outstanding is credit limit minus available limit`() {
        // Avl Limit: INR 15,468.00 against a known INR 50,000.00 credit limit.
        val outstanding = outstandingFromAvailableLimit(creditLimit = 5_000_000L, availableLimit = 1_546_800L)

        assertThat(outstanding).isEqualTo(3_453_200L)
    }

    @Test
    fun `a fully available limit means zero outstanding`() {
        val outstanding = outstandingFromAvailableLimit(creditLimit = 5_000_000L, availableLimit = 5_000_000L)

        assertThat(outstanding).isEqualTo(0L)
    }
}
