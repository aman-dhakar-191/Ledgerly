package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Task 2.6's own test: "settlement debit links as a transfer." */
class BnplSettlementTest {

    @Test
    fun `CAPITALFLOAT is recognised regardless of case`() {
        assertThat(isBnplSettlementMerchant("CAPITALFLOAT")).isTrue()
        assertThat(isBnplSettlementMerchant("capitalfloat")).isTrue()
        assertThat(isBnplSettlementMerchant(" CapitalFloat ")).isTrue()
    }

    @Test
    fun `an unrelated merchant is not a BNPL settlement`() {
        assertThat(isBnplSettlementMerchant("BLINKIT")).isFalse()
    }

    @Test
    fun `a null merchant is not a BNPL settlement`() {
        assertThat(isBnplSettlementMerchant(null)).isFalse()
    }
}
