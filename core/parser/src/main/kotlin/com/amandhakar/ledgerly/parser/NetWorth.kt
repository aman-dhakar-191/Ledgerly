package com.amandhakar.ledgerly.parser

/** tasks/phase-2.md's own threshold isn't specified numerically; 30 days matches a monthly statement cadence. */
private const val STALE_THRESHOLD_MILLIS = 30L * 24 * 60 * 60 * 1000L

/**
 * One account's contribution to net worth - already signed correctly (docs/schema.md's
 * `current_balance`: "negative for liabilities"), so summing every component's [amount] directly
 * yields the total. The caller ([com.amandhakar.ledgerly.ledger.NetWorthCalculator]) is responsible
 * for deciding which accounts qualify as a component at all (Task 2.9: an account with no anchor
 * and no balance-carrying message is excluded, never silently treated as zero) and for picking the
 * right amount/asOf per account type (a BNPL account's outstanding comes from its latest statement,
 * not its rarely-updated `current_balance` - Task 2.6 never wired axio spends to reanchor it).
 */
data class NetWorthComponent(val accountId: String, val amount: Long, val asOf: Long)

data class NetWorthResult(val total: Long, val staleAccountIds: Set<String>)

/**
 * tasks/phase-2.md: "a net worth figure built from stale components is misleading unless the
 * staleness is visible" - [NetWorthResult.staleAccountIds] flags rather than hides or excludes
 * them, since a stale-but-known balance is still better than treating the account as absent.
 */
fun computeNetWorth(components: List<NetWorthComponent>, now: Long): NetWorthResult {
    val total = components.sumOf { it.amount }
    val stale = components.filter { now - it.asOf > STALE_THRESHOLD_MILLIS }.map { it.accountId }.toSet()
    return NetWorthResult(total, stale)
}
