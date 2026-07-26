package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Task 2.8's own test: "partial refund reduces the original correctly." */
class RefundNettingTest {

    @Test
    fun `a partial refund reduces the original spend by exactly the refunded amount`() {
        assertThat(effectiveAmount(originalAmount = 1_000_00L, refundedAmount = 300_00L)).isEqualTo(700_00L)
    }

    @Test
    fun `a full refund reduces the effective amount to zero`() {
        assertThat(effectiveAmount(originalAmount = 500_00L, refundedAmount = 500_00L)).isEqualTo(0L)
    }

    @Test
    fun `a refund larger than the spend never produces a negative effective amount`() {
        assertThat(effectiveAmount(originalAmount = 200_00L, refundedAmount = 500_00L)).isEqualTo(0L)
    }
}
