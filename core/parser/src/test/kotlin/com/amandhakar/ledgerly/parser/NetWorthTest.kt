package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Task 2.9's own test: "net worth reflects card outstanding as negative; stale components are flagged." */
class NetWorthTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a negative card outstanding component reduces the total`() {
        val components = listOf(
            NetWorthComponent(accountId = "savings", amount = 100_000L, asOf = now),
            NetWorthComponent(accountId = "card", amount = -30_000L, asOf = now),
        )

        val result = computeNetWorth(components, now)

        assertThat(result.total).isEqualTo(70_000L)
    }

    @Test
    fun `a component older than 30 days is flagged stale`() {
        val components = listOf(
            NetWorthComponent(accountId = "fresh", amount = 50_000L, asOf = now - 10 * day),
            NetWorthComponent(accountId = "stale", amount = 50_000L, asOf = now - 40 * day),
        )

        val result = computeNetWorth(components, now)

        assertThat(result.staleAccountIds).containsExactly("stale")
    }

    @Test
    fun `an empty component list nets to zero with nothing stale`() {
        val result = computeNetWorth(emptyList(), now)

        assertThat(result.total).isEqualTo(0L)
        assertThat(result.staleAccountIds).isEmpty()
    }
}
