package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AnchorPrefillTest {

    private val ledgerStartDate = 1_700_000_000_000L

    private fun billMessage(receivedAt: Long) = SourceMessage(
        "ICICIT",
        "ICICI Bank Acc XX924 debited Rs. 5000.00 on 12-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 45,231.50",
        receivedAt,
    )

    private fun noBalanceMessage(receivedAt: Long) = SourceMessage(
        "ICICIT",
        "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1",
        receivedAt,
    )

    @Test
    fun `pre-fill picks the earliest qualifying message, not just the first balance-carrying one`() {
        val messages = listOf(
            billMessage(ledgerStartDate + 5_000),
            billMessage(ledgerStartDate + 1_000),
            billMessage(ledgerStartDate + 3_000),
        )

        val prefill = selectAnchorPrefill(messages, ledgerStartDate)

        assertThat(prefill?.asOf).isEqualTo(ledgerStartDate + 1_000)
    }

    @Test
    fun `a message with no balance is skipped in favour of a later one that has a balance`() {
        val messages = listOf(
            noBalanceMessage(ledgerStartDate + 1_000),
            billMessage(ledgerStartDate + 2_000),
        )

        val prefill = selectAnchorPrefill(messages, ledgerStartDate)

        assertThat(prefill?.asOf).isEqualTo(ledgerStartDate + 2_000)
        assertThat(prefill?.balance).isEqualTo(4_523_150L)
    }

    @Test
    fun `messages before ledger_start_date are never used to pre-fill`() {
        val messages = listOf(
            billMessage(ledgerStartDate - 1_000),
            billMessage(ledgerStartDate + 2_000),
        )

        val prefill = selectAnchorPrefill(messages, ledgerStartDate)

        assertThat(prefill?.asOf).isEqualTo(ledgerStartDate + 2_000)
    }

    @Test
    fun `no qualifying message returns null so the caller falls back to a manual opening balance`() {
        val messages = listOf(
            billMessage(ledgerStartDate - 5_000),
            noBalanceMessage(ledgerStartDate + 1_000),
        )

        assertThat(selectAnchorPrefill(messages, ledgerStartDate)).isNull()
    }

    @Test
    fun `an empty archive returns null`() {
        assertThat(selectAnchorPrefill(emptyList(), ledgerStartDate)).isNull()
    }
}
