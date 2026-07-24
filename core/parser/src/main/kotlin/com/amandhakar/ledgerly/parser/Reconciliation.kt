package com.amandhakar.ledgerly.parser

/**
 * docs/schema.md's reconciliation formula, as pure arithmetic — the caller is responsible for
 * gathering [anchorBalance] (the latest `BalanceAnchor` at or before the transaction, via
 * `BalanceAnchorDao.getLatestAtOrBefore`) and [confirmedTxnSum] (the signed sum of `CONFIRMED`
 * transactions between that anchor and this one, exclusive of this one).
 */
data class ReconciliationInput(
    val anchorBalance: Long,
    val confirmedTxnSum: Long,
    val txnAmount: Long,
    val txnDirection: Direction,
    val statedBalanceAfter: Long?,
)

/**
 * Task 1.11. Most UPI messages carry no balance at all — [NoBalanceStated] is the common case, not
 * an error, and still advances the running balance so later reconciliations build on the right
 * baseline. [Mismatch] means a probable missed SMS in the window; the caller routes the
 * transaction to `PENDING_REVIEW` rather than writing it as `CONFIRMED`.
 */
sealed interface ReconciliationResult {
    data class Confirmed(val newBalance: Long) : ReconciliationResult
    data class Mismatch(val expected: Long, val stated: Long) : ReconciliationResult
    data class NoBalanceStated(val runningBalance: Long) : ReconciliationResult
}

fun reconcile(input: ReconciliationInput): ReconciliationResult {
    val baseline = input.anchorBalance + input.confirmedTxnSum
    val expected = when (input.txnDirection) {
        Direction.DEBIT -> baseline - input.txnAmount
        Direction.CREDIT -> baseline + input.txnAmount
    }
    return when (input.statedBalanceAfter) {
        null -> ReconciliationResult.NoBalanceStated(expected)
        expected -> ReconciliationResult.Confirmed(expected)
        else -> ReconciliationResult.Mismatch(expected, input.statedBalanceAfter)
    }
}
