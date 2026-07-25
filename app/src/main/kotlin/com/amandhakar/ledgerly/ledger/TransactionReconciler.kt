package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Direction as EntityDirection
import com.amandhakar.ledgerly.parser.Direction as ParserDirection
import com.amandhakar.ledgerly.parser.ReconciliationInput
import com.amandhakar.ledgerly.parser.ReconciliationResult
import com.amandhakar.ledgerly.parser.reconcile
import javax.inject.Inject

/**
 * Task 1.11's formula wired to real data: [com.amandhakar.ledgerly.parser.reconcile] is pure
 * arithmetic, so gathering its inputs — "the latest [com.amandhakar.ledgerly.database.entity.BalanceAnchor]
 * at or before this transaction" and "the signed sum of confirmed transactions since" — lives here
 * instead (docs/schema.md).
 */
class TransactionReconciler @Inject constructor(
    private val balanceAnchorDao: BalanceAnchorDao,
    private val transactionDao: TransactionDao,
) {
    /** Null means no anchor exists yet for this account — nothing to reconcile against. */
    suspend fun reconcile(
        accountId: String,
        occurredAt: Long,
        txnAmount: Long,
        txnDirection: ParserDirection,
        statedBalanceAfter: Long?,
    ): ReconciliationResult? {
        val anchor = balanceAnchorDao.getLatestAtOrBefore(accountId, occurredAt) ?: return null
        val signedSum = transactionDao.getSignedSumSinceAnchor(accountId, anchor.asOf, occurredAt)
        return reconcile(
            ReconciliationInput(
                anchorBalance = anchor.balance,
                confirmedTxnSum = signedSum,
                txnAmount = txnAmount,
                txnDirection = txnDirection,
                statedBalanceAfter = statedBalanceAfter,
            ),
        )
    }
}

fun ParserDirection.toEntityDirection(): EntityDirection = when (this) {
    ParserDirection.DEBIT -> EntityDirection.DEBIT
    ParserDirection.CREDIT -> EntityDirection.CREDIT
}

fun EntityDirection.toParserDirection(): ParserDirection = when (this) {
    EntityDirection.DEBIT -> ParserDirection.DEBIT
    EntityDirection.CREDIT -> ParserDirection.CREDIT
}
