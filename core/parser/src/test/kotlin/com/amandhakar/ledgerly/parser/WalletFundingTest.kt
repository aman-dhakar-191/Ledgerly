package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Task 2.5's own test: "funding creates a transfer not an expense." */
class WalletFundingTest {

    @ParameterizedTest
    @ValueSource(strings = ["Amazon Pay", "Amazon Pay Bala", "Amazon Pay Balan", "Amazon Bill Pay", "amazon pay bala"])
    fun `known wallet-funding merchant variants are all recognised`(merchant: String) {
        assertThat(isWalletFundingMerchant(merchant)).isTrue()
    }

    @Test
    fun `an unrelated merchant is not wallet funding`() {
        assertThat(isWalletFundingMerchant("BLINKIT")).isFalse()
    }

    @Test
    fun `a null merchant is not wallet funding`() {
        assertThat(isWalletFundingMerchant(null)).isFalse()
    }
}
