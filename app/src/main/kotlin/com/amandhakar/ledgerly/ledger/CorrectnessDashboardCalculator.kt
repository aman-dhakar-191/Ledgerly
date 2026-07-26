package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.isStale
import javax.inject.Inject
import kotlinx.coroutines.flow.first

data class AccountReconciliation(val accountId: String, val accountName: String, val lastReconciledAt: Long?, val stale: Boolean)

data class StatementMismatch(val accountId: String, val accountName: String, val occurredAt: Long, val amount: Long)

data class CorrectnessReport(
    val accountReconciliations: List<AccountReconciliation>,
    val unmatchedTransferCount: Int,
    val pendingReviewCount: Int,
    val statementMismatches: List<StatementMismatch>,
)

/**
 * Task 2.10: "One screen answering: can I trust these numbers?" - not analytics, a health check,
 * so every figure here is a direct count or the most recent timestamp, never an aggregate that
 * itself needs trusting.
 *
 * - Last reconciled = [Account.balanceAsOf], but only if a [com.amandhakar.ledgerly.database.entity.BalanceAnchor]
 *   actually backs it (Task 2.9's same distinction: a never-anchored account shows "never," not a
 *   fabricated recent timestamp from its zero-value defaults).
 * - "Unmatched transfers" = every [com.amandhakar.ledgerly.database.entity.Transfer] with a null
 *   `to_txn_id` - docs/schema.md's accommodation for a transfer only one side of which ever produced
 *   an SMS (BNPL settlement, Task 2.6) is also, definitionally, the thing worth counting here.
 * - Statement-vs-computed mismatches reuse [TransactionSource.ADJUSTMENT] rows verbatim - Task 2.4's
 *   `reconcileStatementOutstanding` already creates exactly one per mismatch, so this is a filter,
 *   not a new computation.
 */
class CorrectnessDashboardCalculator @Inject constructor(
    private val accountDao: AccountDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val transactionDao: TransactionDao,
    private val transferDao: TransferDao,
) {
    suspend fun compute(now: Long = System.currentTimeMillis()): CorrectnessReport {
        val accounts = accountDao.observeActive().first()
        val reconciliations = accounts.map { resolveReconciliation(it, now) }

        val unmatchedTransferCount = transferDao.observeAll().first().count { it.toTxnId == null }

        val pendingReview = transactionDao.observeByStatus(TransactionStatus.PENDING_REVIEW).first()
        val accountNames = accounts.associate { it.id to it.name }
        val mismatches = pendingReview
            .filter { it.source == TransactionSource.ADJUSTMENT }
            .map { StatementMismatch(it.accountId, accountNames[it.accountId] ?: "Unknown", it.occurredAt, it.amount) }

        return CorrectnessReport(reconciliations, unmatchedTransferCount, pendingReview.size, mismatches)
    }

    private suspend fun resolveReconciliation(account: Account, now: Long): AccountReconciliation {
        val hasAnchor = balanceAnchorDao.observeForAccount(account.id).first().isNotEmpty()
        val lastReconciledAt = account.balanceAsOf.takeIf { hasAnchor }
        val stale = lastReconciledAt != null && isStale(lastReconciledAt, now)
        return AccountReconciliation(account.id, account.name, lastReconciledAt, stale)
    }
}
