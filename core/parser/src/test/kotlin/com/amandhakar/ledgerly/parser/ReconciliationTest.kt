package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReconciliationTest {

    @Test
    fun `matching stated balance confirms the transaction`() {
        val result = reconcile(
            ReconciliationInput(
                anchorBalance = 100_000L,
                confirmedTxnSum = 0L,
                txnAmount = 5_000L,
                txnDirection = Direction.DEBIT,
                statedBalanceAfter = 95_000L,
            ),
        )
        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(95_000L))
    }

    @Test
    fun `a credit adds to the baseline`() {
        val result = reconcile(
            ReconciliationInput(
                anchorBalance = 100_000L,
                confirmedTxnSum = 0L,
                txnAmount = 20_000L,
                txnDirection = Direction.CREDIT,
                statedBalanceAfter = 120_000L,
            ),
        )
        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(120_000L))
    }

    @Test
    fun `confirmed transactions since the anchor shift the baseline`() {
        // Anchor at 100,000; +20,000 credit and -5,000 debit already confirmed since then (net +15,000).
        val result = reconcile(
            ReconciliationInput(
                anchorBalance = 100_000L,
                confirmedTxnSum = 15_000L,
                txnAmount = 10_000L,
                txnDirection = Direction.DEBIT,
                statedBalanceAfter = 105_000L,
            ),
        )
        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(105_000L))
    }

    @Test
    fun `a mismatched stated balance is rejected and flags the expected value`() {
        val result = reconcile(
            ReconciliationInput(
                anchorBalance = 100_000L,
                confirmedTxnSum = 0L,
                txnAmount = 5_000L,
                txnDirection = Direction.DEBIT,
                statedBalanceAfter = 80_000L, // a missed SMS in the window would explain this gap
            ),
        )
        assertThat(result).isEqualTo(ReconciliationResult.Mismatch(expected = 95_000L, stated = 80_000L))
    }

    @Test
    fun `no stated balance still advances the running balance without confirming it`() {
        val result = reconcile(
            ReconciliationInput(
                anchorBalance = 100_000L,
                confirmedTxnSum = 0L,
                txnAmount = 5_000L,
                txnDirection = Direction.DEBIT,
                statedBalanceAfter = null,
            ),
        )
        assertThat(result).isEqualTo(ReconciliationResult.NoBalanceStated(95_000L))
    }
}
